package com.pgms.access;

import com.pgms.config.AppProperties;
import com.pgms.property.PropertyAssignmentRepository;
import com.pgms.tenant.Tenant;
import com.pgms.tenant.TenantRepository;
import com.pgms.user.RoleName;
import com.pgms.user.User;
import com.pgms.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides and stores access events (spec "Rules"): who the punch belongs to, whether the module is on, whether the
 * person is a current resident (or a tracked staff member), the daily entry limit and the exit mode.
 *
 * <p>Concurrency: callers serialise per device ({@link #lockDevice}) so a re-sent upload cannot race itself past the
 * idempotency check, and every decision that counts entries takes a per-person advisory lock, so two simultaneous
 * punches of the same resident cannot both squeeze under the limit. Locks are transaction scoped and always taken in
 * the order device → person, so they cannot deadlock.
 */
@Component
public class AccessEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(AccessEventProcessor.class);
    private static final int LOCK_DEVICE = 7_001;
    private static final int LOCK_TENANT = 7_002;
    private static final int LOCK_USER = 7_003;

    private final AccessSettingsRepository settingsRepository;
    private final AccessCredentialRepository credentialRepository;
    private final AccessMemberLimitRepository memberLimitRepository;
    private final AccessEventRepository eventRepository;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PropertyAssignmentRepository assignmentRepository;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final AccessProperties config;
    private final ZoneId zone;
    private final Clock clock;

    public AccessEventProcessor(AccessSettingsRepository settingsRepository, AccessCredentialRepository credentialRepository,
                                AccessMemberLimitRepository memberLimitRepository, AccessEventRepository eventRepository,
                                TenantRepository tenantRepository, UserRepository userRepository,
                                PropertyAssignmentRepository assignmentRepository, JdbcTemplate jdbc,
                                ApplicationEventPublisher events, AccessProperties config, AppProperties properties,
                                Clock clock) {
        this.settingsRepository = settingsRepository;
        this.credentialRepository = credentialRepository;
        this.memberLimitRepository = memberLimitRepository;
        this.eventRepository = eventRepository;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.assignmentRepository = assignmentRepository;
        this.jdbc = jdbc;
        this.events = events;
        this.config = config;
        this.zone = properties.businessTimezone();
        this.clock = clock;
    }

    /** The person behind a punch, or why there is none that may pass. */
    public record Subject(Long tenantId, Long userId, String name, AccessResult denial, String denialReason) {
    }

    /** {@code entriesToday}/{@code limit} describe the resident's day before this event (0 / null for others). */
    public record Decision(AccessResult result, String reason, long entriesToday, Integer limit) {
        public boolean allowed() {
            return result == AccessResult.ALLOWED;
        }
    }

    /** A stored (or, for {@code duplicate}, previously stored) event. {@code decision} is null for duplicates. */
    public record Recorded(AccessEvent event, Decision decision, boolean duplicate, String personName) {
    }

    public AccessSettings settingsFor(Long propertyId) {
        return settingsRepository.findById(propertyId).orElseGet(() -> AccessSettings.defaults(propertyId));
    }

    public ZoneId zone() {
        return zone;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockDevice(Long deviceId) {
        lock(LOCK_DEVICE, deviceId);
    }

    // ---- resolution ----

    /** Maps a device user id to the resident / staff member of the property's ACTIVE credential. */
    public Subject resolve(AccessSettings settings, Long organizationId, Long propertyId, String deviceUserId) {
        Optional<AccessCredential> credential = credentialRepository
                .findFirstByPropertyIdAndDeviceUserIdAndStatus(propertyId, deviceUserId, CredentialStatus.ACTIVE);
        if (credential.isEmpty()) {
            return new Subject(null, null, null, AccessResult.UNKNOWN_CREDENTIAL,
                    "No active credential for device user id " + deviceUserId);
        }
        AccessCredential c = credential.get();
        if (c.getTenantId() != null) {
            return tenantRepository.findById(c.getTenantId())
                    .map(t -> forTenant(t, propertyId))
                    .orElseGet(() -> new Subject(c.getTenantId(), null, null, AccessResult.DENIED_NOT_RESIDENT,
                            "Resident profile no longer exists"));
        }
        return userRepository.findById(c.getUserId())
                .map(u -> forStaff(u, settings, organizationId, propertyId))
                .orElseGet(() -> new Subject(null, c.getUserId(), null, AccessResult.DENIED_NOT_RESIDENT,
                        "Staff account no longer exists"));
    }

    public Subject forTenant(Tenant tenant, Long propertyId) {
        if (!tenant.isResident() || !Objects.equals(tenant.getPropertyId(), propertyId)) {
            return new Subject(tenant.getId(), null, tenant.getFullName(), AccessResult.DENIED_NOT_RESIDENT,
                    "Not a current resident of this property (status " + tenant.getStatus() + ")");
        }
        return new Subject(tenant.getId(), null, tenant.getFullName(), null, null);
    }

    private Subject forStaff(User user, AccessSettings settings, Long organizationId, Long propertyId) {
        if (!settings.isTrackStaff()) {
            return new Subject(null, user.getId(), user.getFullName(), AccessResult.DENIED_NOT_RESIDENT,
                    "Staff tracking is switched off for this property");
        }
        boolean belongs = user.isActive() && Objects.equals(user.getOrganizationId(), organizationId)
                && (user.getRoleName() == RoleName.PROPERTY_ADMIN
                    || user.getRoleName().isPropertyScoped() && assignmentRepository.existsByPropertyIdAndUserId(propertyId, user.getId()));
        if (!belongs) {
            return new Subject(null, user.getId(), user.getFullName(), AccessResult.DENIED_NOT_RESIDENT,
                    "Staff member is not active or not assigned to this property");
        }
        return new Subject(null, user.getId(), user.getFullName(), null, null);
    }

    // ---- decision ----

    /**
     * Applies rules 1-4. {@code bypassLimit} is used by manual overrides: the entry is allowed but the reason notes
     * that the limit was already used up.
     */
    public Decision decide(AccessSettings settings, Subject subject, AccessDirection direction, AccessMethod method,
                           Instant occurredAt, boolean bypassLimit) {
        if (!settings.isEnabled()) {
            return new Decision(AccessResult.DENIED_DISABLED, "Access control is switched off for this property", 0, null);
        }
        if (subject.denial() != null) {
            return new Decision(subject.denial(), subject.denialReason(), 0, null);
        }
        long entries = subject.tenantId() == null ? 0 : entriesOn(subject.tenantId(), occurredAt);
        Integer limit = subject.tenantId() == null ? null : limitFor(subject.tenantId(), settings);
        if (direction == AccessDirection.ENTRY) {
            boolean reached = AccessRules.limitReached(entries, limit);
            if (reached && !bypassLimit) {
                return new Decision(AccessResult.DENIED_LIMIT_EXCEEDED,
                        "Daily entry limit reached (" + entries + " of " + limit + " used)", entries, limit);
            }
            return new Decision(AccessResult.ALLOWED, reached ? "Over the daily entry limit (" + entries + " of " + limit
                    + " used)" : null, entries, limit);
        }
        AccessResult exit = AccessRules.exitResult(settings.getExitMode(), method);
        if (exit != AccessResult.ALLOWED) {
            return new Decision(exit, "Exit mode is " + settings.getExitMode() + ": "
                    + (method.isDevice() ? "device exits are not counted" : "the exit button is not available"), entries, limit);
        }
        Presence before = presence(subject, settings.getPropertyId());
        return new Decision(AccessResult.ALLOWED, before == Presence.IN ? null : "Already out", entries, limit);
    }

    // ---- recording ----

    /**
     * Stores one device punch idempotently: a punch already received from this device (same device user id, time and
     * direction) is returned as a duplicate instead of being stored again. The caller holds the device lock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Recorded recordDevicePunch(AccessDevice device, String deviceUserId, AccessDirection direction, AccessMethod method,
                                      Instant occurredAt) {
        Optional<AccessEvent> existing = eventRepository.findFirstByDeviceIdAndDeviceUserIdAndOccurredAtAndDirection(
                device.getId(), deviceUserId, occurredAt, direction);
        if (existing.isPresent()) {
            return new Recorded(existing.get(), null, true, null);
        }
        AccessSettings settings = settingsFor(device.getPropertyId());
        Subject subject = resolve(settings, device.getOrganizationId(), device.getPropertyId(), deviceUserId);
        lockPerson(subject);
        Decision decision = decide(settings, subject, direction, method, occurredAt, false);
        String reason = decision.reason();
        if (occurredAt.isAfter(clock.instant().plus(config.maxClockSkew()))) {
            log.warn("Device {} sent a punch dated {} - its clock is ahead of server time", device.getSerialNumber(), occurredAt);
            reason = (reason == null ? "" : reason + "; ") + "device clock ahead of server time";
        }
        AccessEvent.Draft draft = new AccessEvent.Draft(device.getOrganizationId(), device.getPropertyId(), device.getId(),
                subject.tenantId(), subject.userId(), deviceUserId, direction, method, occurredAt, null);
        return store(draft, decision, reason, subject.name());
    }

    /** Button exits and manual override entries for a resident (the caller has checked permissions). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Recorded recordForTenant(Tenant tenant, AccessSettings settings, AccessDirection direction, AccessMethod method,
                                    Instant occurredAt, Long recordedBy, String note, boolean bypassLimit) {
        Subject subject = forTenant(tenant, settings.getPropertyId());
        lockPerson(subject);
        Decision decision = decide(settings, subject, direction, method, occurredAt, bypassLimit);
        String reason = note == null ? decision.reason()
                : decision.reason() == null ? note : note + " (" + decision.reason() + ")";
        AccessEvent.Draft draft = new AccessEvent.Draft(tenant.getOrganizationId(), settings.getPropertyId(), null,
                tenant.getId(), null, null, direction, method, occurredAt, recordedBy);
        return store(draft, decision, reason, tenant.getFullName());
    }

    private Recorded store(AccessEvent.Draft draft, Decision decision, String reason, String personName) {
        AccessEvent event = eventRepository.save(new AccessEvent(draft, clock.instant(), decision.result(), reason));
        if (decision.result() == AccessResult.DENIED_LIMIT_EXCEEDED
                && !draft.occurredAt().isBefore(clock.instant().minus(config.alertMaxAge()))) {
            events.publishEvent(new AccessEvents.EntryLimitExceeded(event.getId(), draft.organizationId(), draft.propertyId(),
                    draft.tenantId(), personName, draft.occurredAt(), decision.entriesToday(), decision.limit(), draft.method()));
        }
        return new Recorded(event, decision, false, personName);
    }

    // ---- queries ----

    /** Allowed entries of the resident on the business day (Asia/Kolkata) containing {@code at}. */
    public long entriesOn(Long tenantId, Instant at) {
        AccessRules.Day day = AccessRules.businessDay(at, zone);
        return eventRepository.countByTenantIdAndDirectionAndResultAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
                tenantId, AccessDirection.ENTRY, AccessResult.ALLOWED, day.start(), day.end());
    }

    public Integer limitFor(Long tenantId, AccessSettings settings) {
        Optional<AccessMemberLimit> override = memberLimitRepository.findById(tenantId);
        return AccessRules.effectiveLimit(override.isPresent(), override.map(AccessMemberLimit::getMaxEntriesPerDay).orElse(null),
                settings.getMaxEntriesPerDay());
    }

    public Optional<AccessEvent> lastAllowed(Long tenantId, Long propertyId) {
        return eventRepository.findFirstByTenantIdAndPropertyIdAndResultOrderByOccurredAtDescIdDesc(tenantId, propertyId,
                AccessResult.ALLOWED);
    }

    private Presence presence(Subject subject, Long propertyId) {
        Optional<AccessEvent> last = subject.tenantId() != null
                ? lastAllowed(subject.tenantId(), propertyId)
                : eventRepository.findFirstByUserIdAndPropertyIdAndResultOrderByOccurredAtDescIdDesc(subject.userId(), propertyId,
                        AccessResult.ALLOWED);
        return last.map(e -> e.getDirection() == AccessDirection.ENTRY ? Presence.IN : Presence.OUT).orElse(Presence.UNKNOWN);
    }

    private void lockPerson(Subject subject) {
        if (subject.tenantId() != null) {
            lock(LOCK_TENANT, subject.tenantId());
        } else if (subject.userId() != null) {
            lock(LOCK_USER, subject.userId());
        }
    }

    private void lock(int namespace, long id) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(?, ?)", namespace, (int) (id ^ (id >>> 32)));
    }
}
