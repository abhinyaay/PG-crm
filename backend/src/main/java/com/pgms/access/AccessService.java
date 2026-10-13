package com.pgms.access;

import com.pgms.access.AccessDtos.AccessEventResponse;
import com.pgms.access.AccessDtos.ExitRequest;
import com.pgms.access.AccessDtos.ManualEntryRequest;
import com.pgms.access.AccessDtos.MyAccessResponse;
import com.pgms.access.AccessDtos.PresenceEntry;
import com.pgms.access.AccessDtos.PresenceResponse;
import com.pgms.audit.AuditService;
import com.pgms.common.api.PageResponse;
import com.pgms.common.api.SortValidator;
import com.pgms.common.exception.BadRequestException;
import com.pgms.common.exception.BusinessRuleException;
import com.pgms.common.exception.NotFoundException;
import com.pgms.security.AccessGuard;
import com.pgms.security.AccessScope;
import com.pgms.security.CurrentUser;
import com.pgms.tenant.Tenant;
import com.pgms.tenant.TenantRepository;
import com.pgms.user.User;
import com.pgms.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Gate log, presence, the resident's own status and the app-driven events (exit button, manual override entry). */
@Service
public class AccessService {

    private static final Set<String> SORTABLE = Set.of("occurredAt", "receivedAt");

    private final AccessEventRepository eventRepository;
    private final AccessDeviceRepository deviceRepository;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final AccessEventProcessor processor;
    private final PresenceQuery presenceQuery;
    private final AccessGuard accessGuard;
    private final AuditService auditService;
    private final Clock clock;

    public AccessService(AccessEventRepository eventRepository, AccessDeviceRepository deviceRepository,
                         TenantRepository tenantRepository, UserRepository userRepository, AccessEventProcessor processor,
                         PresenceQuery presenceQuery, AccessGuard accessGuard, AuditService auditService, Clock clock) {
        this.eventRepository = eventRepository;
        this.deviceRepository = deviceRepository;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.processor = processor;
        this.presenceQuery = presenceQuery;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
        this.clock = clock;
    }

    // ---- gate log ----

    /** {@code from}/{@code to} are inclusive business dates (Asia/Kolkata). Tenants only ever see their own events. */
    @Transactional(readOnly = true)
    public PageResponse<AccessEventResponse> events(Long propertyId, Long tenantId, AccessDirection direction, AccessResult result,
                                                    AccessMethod method, LocalDate from, LocalDate to, Pageable pageable) {
        Pageable page = SortValidator.validate(pageable, SORTABLE, Sort.by(Sort.Direction.DESC, "occurredAt"));
        if (from != null && to != null && from.isAfter(to)) {
            throw new BadRequestException("INVALID_DATE_RANGE", "'from' must not be after 'to'");
        }
        Specification<AccessEvent> spec;
        if (accessGuard.isTenant()) {
            Long own = accessGuard.requireCurrentTenant().getId();
            spec = (root, query, cb) -> cb.equal(root.get("tenantId"), own);
        } else {
            AccessScope scope = accessGuard.scopeFor(propertyId);
            if (scope.isEmpty()) {
                return PageResponse.from(Page.<AccessEvent>empty(page), e -> AccessEventResponse.of(e, null, null));
            }
            spec = scope.toSpecification();
            if (tenantId != null) {
                spec = spec.and((root, query, cb) -> cb.equal(root.get("tenantId"), tenantId));
            }
        }
        if (direction != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("direction"), direction));
        }
        if (result != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("result"), result));
        }
        if (method != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("method"), method));
        }
        if (from != null) {
            Instant start = from.atStartOfDay(processor.zone()).toInstant();
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("occurredAt"), start));
        }
        if (to != null) {
            Instant end = to.plusDays(1).atStartOfDay(processor.zone()).toInstant();
            spec = spec.and((root, query, cb) -> cb.lessThan(root.get("occurredAt"), end));
        }
        Page<AccessEvent> events = eventRepository.findAll(spec, page);
        Names names = names(events.getContent());
        return PageResponse.from(events, names::response);
    }

    // ---- presence ----

    @Transactional(readOnly = true)
    public PresenceResponse presence(Long propertyId) {
        accessGuard.requirePropertyAccess(propertyId);
        AccessSettings settings = processor.settingsFor(propertyId);
        List<PresenceQuery.Row> rows = presenceQuery.inside(propertyId);
        Map<Long, Tenant> tenants = byId(tenantRepository.findAllById(ids(rows, PresenceQuery.Row::tenantId)),
                Tenant::getId);
        Map<Long, User> users = byId(userRepository.findAllById(ids(rows, PresenceQuery.Row::userId)), User::getId);
        Map<Long, AccessDevice> devices = byId(deviceRepository.findAllById(ids(rows, PresenceQuery.Row::deviceId)),
                AccessDevice::getId);
        List<PresenceEntry> inside = new ArrayList<>();
        for (PresenceQuery.Row row : rows) {
            String deviceName = row.deviceId() == null ? null
                    : Optional.ofNullable(devices.get(row.deviceId())).map(AccessDevice::getName).orElse(null);
            AccessMethod method = row.method();
            if (row.tenantId() != null) {
                Tenant tenant = tenants.get(row.tenantId());
                // people who moved out or moved to another property are no longer "inside" this one
                if (tenant != null && tenant.isResident() && Objects.equals(tenant.getPropertyId(), propertyId)) {
                    inside.add(new PresenceEntry("RESIDENT", tenant.getId(), null, tenant.getFullName(), row.since(), method,
                            row.deviceId(), deviceName, row.eventId()));
                }
            } else if (settings.isTrackStaff()) {
                User user = users.get(row.userId());
                if (user != null && user.isActive()) {
                    inside.add(new PresenceEntry("STAFF", null, user.getId(), user.getFullName(), row.since(), method,
                            row.deviceId(), deviceName, row.eventId()));
                }
            }
        }
        return new PresenceResponse(propertyId, settings.isEnabled(), inside.size(), inside);
    }

    // ---- resident ----

    @Transactional(readOnly = true)
    public MyAccessResponse me() {
        Tenant tenant = accessGuard.requireCurrentTenant();
        AccessSettings settings = processor.settingsFor(tenant.getPropertyId());
        AccessEventResponse lastEvent = eventRepository.findFirstByTenantIdOrderByOccurredAtDescIdDesc(tenant.getId())
                .map(e -> names(List.of(e)).response(e)).orElse(null);
        if (!settings.isEnabled()) {
            return new MyAccessResponse(false, tenant.getPropertyId(), Presence.UNKNOWN, null, 0, null, null,
                    settings.getExitMode(), false, null);
        }
        Optional<AccessEvent> last = processor.lastAllowed(tenant.getId(), tenant.getPropertyId());
        Presence presence = last.map(e -> e.getDirection() == AccessDirection.ENTRY ? Presence.IN : Presence.OUT)
                .orElse(Presence.UNKNOWN);
        long entries = processor.entriesOn(tenant.getId(), clock.instant());
        Integer limit = processor.limitFor(tenant.getId(), settings);
        return new MyAccessResponse(true, tenant.getPropertyId(), presence, last.map(AccessEvent::getOccurredAt).orElse(null),
                entries, limit, AccessRules.remaining(entries, limit), settings.getExitMode(),
                settings.getExitMode().allowsButton() && tenant.isResident(), lastEvent);
    }

    /** "I'm leaving": the resident themselves, or an operator on their behalf ({@code tenantId}). */
    @Transactional
    public AccessEventResponse exit(ExitRequest request) {
        CurrentUser caller = accessGuard.currentUser();
        Tenant tenant;
        Long recordedBy = null;
        if (accessGuard.isTenant()) {
            tenant = accessGuard.requireCurrentTenant();
        } else {
            if (request == null || request.tenantId() == null) {
                throw new BadRequestException("TENANT_ID_REQUIRED", "tenantId is required when staff record an exit");
            }
            tenant = loadOperatorAccessibleTenant(request.tenantId());
            recordedBy = caller.id();
        }
        AccessSettings settings = processor.settingsFor(tenant.getPropertyId());
        requireEnabled(settings);
        if (!settings.getExitMode().allowsButton()) {
            throw new BusinessRuleException("EXIT_BUTTON_NOT_ALLOWED",
                    "This property records exits only with the biometric device (exit mode " + settings.getExitMode() + ")");
        }
        requireResident(tenant);
        AccessEventProcessor.Recorded recorded = processor.recordForTenant(tenant, settings, AccessDirection.EXIT,
                AccessMethod.BUTTON, clock.instant().truncatedTo(ChronoUnit.SECONDS), recordedBy,
                recordedBy == null ? null : "Recorded by staff on behalf of the resident", false);
        if (recordedBy != null) {
            auditService.record(tenant.getOrganizationId(), "ACCESS_EXIT_RECORDED", "TENANT", tenant.getId(),
                    Map.of("eventId", recorded.event().getId()));
        }
        return names(List.of(recorded.event())).response(recorded.event());
    }

    /** Manager's audited override entry (device broken, guest of honour, ...). Bypasses but reports the daily limit. */
    @Transactional
    public AccessEventResponse manualEntry(ManualEntryRequest request) {
        Tenant tenant = loadOperatorAccessibleTenant(request.tenantId());
        AccessSettings settings = processor.settingsFor(tenant.getPropertyId());
        requireEnabled(settings);
        requireResident(tenant);
        Instant occurredAt = (request.occurredAt() != null ? request.occurredAt() : clock.instant()).truncatedTo(ChronoUnit.SECONDS);
        String reason = request.reason().trim();
        AccessEventProcessor.Recorded recorded = processor.recordForTenant(tenant, settings, AccessDirection.ENTRY,
                AccessMethod.MANUAL_OVERRIDE, occurredAt, accessGuard.currentUser().id(), "Manual override: " + reason, true);
        AccessEventProcessor.Decision decision = recorded.decision();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("eventId", recorded.event().getId());
        details.put("reason", reason);
        details.put("occurredAt", occurredAt.toString());
        details.put("overDailyLimit", AccessRules.limitReached(decision.entriesToday(), decision.limit()));
        auditService.record(tenant.getOrganizationId(), "ACCESS_MANUAL_ENTRY", "TENANT", tenant.getId(), details);
        return names(List.of(recorded.event())).response(recorded.event());
    }

    private Tenant loadOperatorAccessibleTenant(Long tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId).orElseThrow(() -> new NotFoundException("Tenant", tenantId));
        accessGuard.requirePropertyAccess(tenant.getPropertyId());
        return tenant;
    }

    private static void requireEnabled(AccessSettings settings) {
        if (!settings.isEnabled()) {
            throw new BusinessRuleException("ACCESS_CONTROL_DISABLED", "Access control is switched off for this property");
        }
    }

    private static void requireResident(Tenant tenant) {
        if (!tenant.isResident()) {
            throw new BusinessRuleException("TENANT_NOT_RESIDENT", "Only checked-in residents can be recorded at the gate");
        }
    }

    // ---- name lookups for responses (one query per entity type per page) ----

    private record Names(Map<Long, String> devices, Map<Long, String> tenants, Map<Long, String> users) {
        AccessEventResponse response(AccessEvent e) {
            String person = e.getTenantId() != null ? tenants.get(e.getTenantId())
                    : e.getUserId() != null ? users.get(e.getUserId()) : null;
            return AccessEventResponse.of(e, e.getDeviceId() == null ? null : devices.get(e.getDeviceId()), person);
        }
    }

    private Names names(Collection<AccessEvent> events) {
        Map<Long, String> devices = new HashMap<>();
        deviceRepository.findAllById(ids(events, AccessEvent::getDeviceId)).forEach(d -> devices.put(d.getId(), d.getName()));
        Map<Long, String> tenants = new HashMap<>();
        tenantRepository.findAllById(ids(events, AccessEvent::getTenantId)).forEach(t -> tenants.put(t.getId(), t.getFullName()));
        Map<Long, String> users = new HashMap<>();
        userRepository.findAllById(ids(events, AccessEvent::getUserId)).forEach(u -> users.put(u.getId(), u.getFullName()));
        return new Names(devices, tenants, users);
    }

    private static <T> Set<Long> ids(Collection<T> items, Function<T, Long> id) {
        return items.stream().map(id).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private static <T> Map<Long, T> byId(Iterable<T> items, Function<T, Long> id) {
        Map<Long, T> map = new HashMap<>();
        items.forEach(i -> map.put(id.apply(i), i));
        return map;
    }
}
