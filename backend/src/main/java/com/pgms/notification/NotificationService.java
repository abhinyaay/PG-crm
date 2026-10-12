package com.pgms.notification;

import com.pgms.common.api.PageResponse;
import com.pgms.common.exception.NotFoundException;
import com.pgms.common.util.PhoneNumbers;
import com.pgms.config.AppProperties;
import com.pgms.notification.sms.SmsTemplates;
import com.pgms.organization.Organization;
import com.pgms.organization.OrganizationRepository;
import com.pgms.security.CurrentUserProvider;
import com.pgms.tenant.Tenant;
import com.pgms.tenant.TenantRepository;
import com.pgms.user.RoleName;
import com.pgms.user.User;
import com.pgms.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates outbox rows (in the caller's transaction) and serves the in-app inbox.
 *
 * <p>For every recipient each channel goes through {@link NotificationPolicy#shouldSend} (mandatory → organization
 * setting → user preference → matrix default) and is then skipped if there is no usable address, the address bounced
 * or opted out, or (SMS) there is no fixed template for the type. Non-urgent SMS created during quiet hours gets its
 * first attempt scheduled for the end of the quiet window.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repository;
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final OrganizationRepository organizationRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final OrganizationNotificationSettingRepository settingRepository;
    private final NotificationPolicy policy;
    private final SmsTemplates smsTemplates;
    private final QuietHours quietHours;
    private final CurrentUserProvider currentUserProvider;
    private final AppProperties.Notifications config;
    private final Clock clock;

    public NotificationService(NotificationRepository repository, UserRepository userRepository,
                               TenantRepository tenantRepository, OrganizationRepository organizationRepository,
                               NotificationPreferenceRepository preferenceRepository,
                               OrganizationNotificationSettingRepository settingRepository, NotificationPolicy policy,
                               SmsTemplates smsTemplates, QuietHours quietHours, CurrentUserProvider currentUserProvider,
                               AppProperties properties, Clock clock) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.organizationRepository = organizationRepository;
        this.preferenceRepository = preferenceRepository;
        this.settingRepository = settingRepository;
        this.policy = policy;
        this.smsTemplates = smsTemplates;
        this.quietHours = quietHours;
        this.currentUserProvider = currentUserProvider;
        this.config = properties.notifications();
        this.clock = clock;
    }

    /**
     * A contact resolved for one notification. {@code userId} enables the in-app inbox and personal preferences;
     * tenants without a login have none and get the matrix defaults.
     */
    record Recipient(Long organizationId, Long userId, Audience audience, String name, String email, boolean emailSuppressed,
                     String phone, boolean smsSuppressed) {
    }

    /** A platform user: in-app, their email, and their phone (a resident login falls back to the tenant's phone). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyUser(Long userId, NotificationMessage message) {
        userRepository.findById(userId).filter(User::isActive).ifPresent(user -> deliver(recipientFor(user), message));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyUsers(Collection<User> users, NotificationMessage message) {
        users.forEach(u -> notifyUser(u.getId(), message));
    }

    /**
     * A resident: in-app only when they have a login, email when known, SMS to the (mandatory) tenant phone - the one
     * channel that always reaches a resident.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyTenant(Long tenantId, NotificationMessage message) {
        tenantRepository.findById(tenantId).ifPresent(tenant -> deliver(recipientFor(tenant), message));
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> inbox(boolean unreadOnly, Pageable pageable) {
        Long userId = currentUserProvider.require().id();
        // id as tie-breaker: one business transaction creates several rows with the same timestamp
        Pageable page = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        var result = unreadOnly
                ? repository.findByRecipientUserIdAndChannelAndReadAtIsNull(userId, NotificationChannel.IN_APP, page)
                : repository.findByRecipientUserIdAndChannel(userId, NotificationChannel.IN_APP, page);
        return PageResponse.from(result, NotificationResponse::of);
    }

    @Transactional(readOnly = true)
    public long unreadCount() {
        return repository.countByRecipientUserIdAndChannelAndReadAtIsNull(currentUserProvider.require().id(),
                NotificationChannel.IN_APP);
    }

    @Transactional
    public NotificationResponse markRead(Long id) {
        Long userId = currentUserProvider.require().id();
        Notification notification = repository.findByIdAndRecipientUserId(id, userId)
                .orElseThrow(() -> new NotFoundException("Notification", id));
        notification.markRead(clock.instant());
        return NotificationResponse.of(notification);
    }

    @Transactional
    public int markAllRead() {
        return repository.markAllRead(currentUserProvider.require().id(), clock.instant());
    }

    private Recipient recipientFor(User user) {
        String phone = user.getPhone();
        boolean smsOptOut = user.getSmsOptOutAt() != null;
        if (user.getRoleName() == RoleName.TENANT) {
            Optional<Tenant> tenant = tenantRepository.findByUserId(user.getId());
            if (tenant.isPresent()) {
                phone = phone == null || phone.isBlank() ? tenant.get().getPhone() : phone;
                smsOptOut = smsOptOut || tenant.get().getSmsOptOutAt() != null;
            }
        }
        return new Recipient(user.getOrganizationId(), user.getId(), Audience.of(user.getRoleName()), user.getFullName(),
                user.getEmail(), user.getEmailBouncedAt() != null, phone, smsOptOut);
    }

    private Recipient recipientFor(Tenant tenant) {
        // a login that was deleted or deactivated no longer has an inbox or preferences
        Optional<User> login = tenant.getUserId() == null ? Optional.empty()
                : userRepository.findById(tenant.getUserId()).filter(User::isActive);
        boolean smsOptOut = tenant.getSmsOptOutAt() != null || login.map(u -> u.getSmsOptOutAt() != null).orElse(false);
        return new Recipient(tenant.getOrganizationId(), login.map(User::getId).orElse(null), Audience.RESIDENT,
                tenant.getFullName(), tenant.getEmail(), tenant.getEmailBouncedAt() != null, tenant.getPhone(), smsOptOut);
    }

    private void deliver(Recipient recipient, NotificationMessage original) {
        NotificationType type = original.type();
        NotificationMessage message = withStandardParams(recipient, original);
        Map<NotificationChannel, Boolean> userPreferences = new EnumMap<>(NotificationChannel.class);
        if (recipient.userId() != null) {
            preferenceRepository.findByUserIdAndNotificationType(recipient.userId(), type)
                    .forEach(p -> userPreferences.put(p.getChannel(), p.isEnabled()));
        }
        Map<NotificationChannel, Boolean> organizationSettings = new EnumMap<>(NotificationChannel.class);
        if (recipient.organizationId() != null) {
            settingRepository.findByOrganizationIdAndType(recipient.organizationId(), type)
                    .forEach(s -> organizationSettings.put(s.getId().getChannel(), s.isEnabled()));
        }
        Instant now = clock.instant();
        for (NotificationChannel channel : NotificationPolicy.CHANNELS) {
            if (!message.allows(channel) || !policy.shouldSend(type, recipient.audience(), channel, message.important(),
                    organizationSettings.get(channel), userPreferences.get(channel))) {
                continue;
            }
            switch (channel) {
                case IN_APP -> {
                    if (recipient.userId() != null) {
                        store(recipient.organizationId(), recipient.userId(), null, channel, message, now, now, false);
                    }
                }
                case EMAIL -> {
                    if (hasText(recipient.email()) && !recipient.emailSuppressed()) {
                        store(recipient.organizationId(), null, recipient.email(), channel, message, now, now,
                                isUrgent(message));
                    }
                }
                case SMS -> storeSms(recipient, message, now);
                default -> log.debug("Channel {} has no delivery path", channel);
            }
        }
    }

    private void storeSms(Recipient recipient, NotificationMessage message, Instant now) {
        if (recipient.smsSuppressed()) {
            return;
        }
        Optional<String> phone = PhoneNumbers.toE164(recipient.phone(), config.sms().defaultCountryCode());
        if (phone.isEmpty()) {
            return;
        }
        Optional<SmsTemplates.RenderedSms> sms = smsTemplates.render(message.type(), message.params());
        if (sms.isEmpty()) {
            if (smsTemplates.hasTemplate(message.type())) {
                log.warn("SMS for {} skipped: a template variable is missing", message.type());
            }
            return;
        }
        boolean urgent = isUrgent(message);
        Instant firstAttempt = urgent ? now : quietHours.defer(now);
        // the SMS row's body is the exact (fixed-template) text that will be sent
        NotificationMessage smsMessage = new NotificationMessage(message.type(), message.title(), sms.get().text(),
                message.referenceType(), message.referenceId(), message.dedupKey(), message.params(), message.important(),
                urgent, null);
        store(recipient.organizationId(), null, phone.get(), NotificationChannel.SMS, smsMessage, now, firstAttempt, urgent);
    }

    private boolean isUrgent(NotificationMessage message) {
        return message.urgent() || policy.isUrgent(message.type());
    }

    /** Every template may use {pg} (organization / brand name) and {name} (recipient's first name). */
    private NotificationMessage withStandardParams(Recipient recipient, NotificationMessage message) {
        NotificationMessage enriched = message;
        if (!message.params().containsKey("pg")) {
            String brand = recipient.organizationId() == null ? null
                    : organizationRepository.findById(recipient.organizationId()).map(Organization::getName).orElse(null);
            enriched = enriched.withParam("pg", brand == null ? config.email().brandName() : brand);
        }
        if (!message.params().containsKey("name") && hasText(recipient.name())) {
            enriched = enriched.withParam("name", recipient.name().trim().split("\\s+")[0]);
        }
        return enriched;
    }

    private void store(Long organizationId, Long userId, String address, NotificationChannel channel,
                       NotificationMessage message, Instant now, Instant firstAttempt, boolean urgent) {
        String dedup = message.dedupKey() == null ? null
                : message.dedupKey() + (userId != null ? ":u" + userId : ":a" + address.hashCode());
        NotificationMessage scoped = dedup == null ? message : message.withDedupKey(dedup);
        // check-then-insert is enough here: the only deduplicated producers are ShedLock-guarded jobs
        if (dedup != null && repository.existsByDedupKeyAndChannel(dedup, channel)) {
            return;
        }
        repository.save(new Notification(organizationId, userId, address, channel, scoped, now, firstAttempt, urgent));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record NotificationResponse(Long id, NotificationType type, String title, String body, String referenceType,
                                       Long referenceId, Instant readAt, Instant createdAt) {
        static NotificationResponse of(Notification n) {
            return new NotificationResponse(n.getId(), n.getType(), n.getTitle(), n.getBody(), n.getReferenceType(),
                    n.getReferenceId(), n.getReadAt(), n.getCreatedAt());
        }
    }
}
