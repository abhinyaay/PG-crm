package com.pgms.notification;

import com.pgms.audit.AuditService;
import com.pgms.common.exception.BusinessRuleException;
import com.pgms.common.exception.NotFoundException;
import com.pgms.notification.NotificationDtos.OrganizationSettingItem;
import com.pgms.notification.NotificationDtos.PreferenceChange;
import com.pgms.notification.NotificationDtos.PreferenceItem;
import com.pgms.notification.NotificationPolicy.TypeChannel;
import com.pgms.organization.OrganizationRepository;
import com.pgms.security.AccessGuard;
import com.pgms.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Personal notification preferences and organization-wide channel switches (roadmap section 3). */
@Service
public class NotificationPreferenceService {

    private final NotificationPreferenceRepository preferenceRepository;
    private final OrganizationNotificationSettingRepository settingRepository;
    private final OrganizationRepository organizationRepository;
    private final NotificationPolicy policy;
    private final AccessGuard accessGuard;
    private final AuditService auditService;
    private final Clock clock;

    public NotificationPreferenceService(NotificationPreferenceRepository preferenceRepository,
                                         OrganizationNotificationSettingRepository settingRepository,
                                         OrganizationRepository organizationRepository, NotificationPolicy policy,
                                         AccessGuard accessGuard, AuditService auditService, Clock clock) {
        this.preferenceRepository = preferenceRepository;
        this.settingRepository = settingRepository;
        this.organizationRepository = organizationRepository;
        this.policy = policy;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
        this.clock = clock;
    }

    /** The grid for the caller's role: every type they can receive, on every channel offered for it. */
    @Transactional(readOnly = true)
    public List<PreferenceItem> myPreferences() {
        CurrentUser user = accessGuard.currentUser();
        Audience audience = Audience.of(user.role());
        Map<TypeChannel, Boolean> own = preferenceRepository.findByUserId(user.id()).stream()
                .collect(Collectors.toMap(p -> new TypeChannel(p.getNotificationType(), p.getChannel()),
                        NotificationPreference::isEnabled, (a, b) -> b));
        Map<TypeChannel, Boolean> organization = organizationSwitches(user.organizationId());
        return policy.offered(audience).stream().map(cell -> {
            boolean mandatory = policy.isMandatory(cell.type(), audience, cell.channel());
            boolean orgDisabled = !mandatory && Boolean.FALSE.equals(organization.get(cell));
            boolean enabled = mandatory || own.getOrDefault(cell, policy.defaultEnabled(cell.type(), audience, cell.channel()));
            return new PreferenceItem(cell.type(), cell.channel(), enabled, mandatory, orgDisabled);
        }).toList();
    }

    @Transactional
    public List<PreferenceItem> updateMyPreferences(List<PreferenceChange> changes) {
        CurrentUser user = accessGuard.currentUser();
        Audience audience = Audience.of(user.role());
        List<TypeChannel> offered = policy.offered(audience);
        for (PreferenceChange change : changes) {
            TypeChannel cell = new TypeChannel(change.type(), change.channel());
            if (!offered.contains(cell)) {
                throw new BusinessRuleException("PREFERENCE_NOT_AVAILABLE",
                        change.type() + " is not sent via " + change.channel() + " to your account");
            }
            if (policy.isMandatory(change.type(), audience, change.channel()) && !change.enabled()) {
                throw new BusinessRuleException("PREFERENCE_MANDATORY",
                        change.type() + " via " + change.channel() + " is mandatory and cannot be switched off");
            }
        }
        Instant now = clock.instant();
        Map<TypeChannel, NotificationPreference> existing = new HashMap<>(preferenceRepository.findByUserId(user.id()).stream()
                .collect(Collectors.toMap(p -> new TypeChannel(p.getNotificationType(), p.getChannel()), Function.identity())));
        for (PreferenceChange change : changes) {
            if (policy.isMandatory(change.type(), audience, change.channel())) {
                continue; // "enabled: true" on a mandatory cell is a no-op
            }
            TypeChannel cell = new TypeChannel(change.type(), change.channel());
            NotificationPreference preference = existing.get(cell);
            if (preference == null) {
                existing.put(cell, preferenceRepository.save(
                        new NotificationPreference(user.id(), change.type(), change.channel(), change.enabled(), now)));
            } else {
                preference.change(change.enabled(), now);
            }
        }
        auditService.record(user.organizationId(), "NOTIFICATION_PREFERENCES_UPDATED", "USER", user.id(),
                Map.of("changes", changes.size()));
        return myPreferences();
    }

    @Transactional(readOnly = true)
    public List<OrganizationSettingItem> organizationSettings(Long organizationId) {
        requireOrganization(organizationId);
        Map<TypeChannel, Boolean> switches = organizationSwitches(organizationId);
        return policy.offeredToAnyAudience().stream().map(cell -> {
            boolean mandatory = policy.isMandatoryForAnyAudience(cell.type(), cell.channel());
            return new OrganizationSettingItem(cell.type(), cell.channel(), mandatory || switches.getOrDefault(cell, true), mandatory);
        }).toList();
    }

    @Transactional
    public List<OrganizationSettingItem> updateOrganizationSettings(Long organizationId, List<PreferenceChange> changes) {
        requireOrganization(organizationId);
        List<TypeChannel> offered = policy.offeredToAnyAudience();
        for (PreferenceChange change : changes) {
            if (!offered.contains(new TypeChannel(change.type(), change.channel()))) {
                throw new BusinessRuleException("PREFERENCE_NOT_AVAILABLE",
                        change.type() + " is never sent via " + change.channel());
            }
            if (policy.isMandatoryForAnyAudience(change.type(), change.channel()) && !change.enabled()) {
                throw new BusinessRuleException("PREFERENCE_MANDATORY",
                        change.type() + " via " + change.channel() + " is mandatory and cannot be switched off");
            }
        }
        Instant now = clock.instant();
        Long actor = accessGuard.currentUser().id();
        for (PreferenceChange change : changes) {
            OrganizationNotificationSetting.Key key =
                    new OrganizationNotificationSetting.Key(organizationId, change.type(), change.channel());
            settingRepository.findById(key).ifPresentOrElse(
                    setting -> setting.change(change.enabled(), now, actor),
                    () -> settingRepository.save(new OrganizationNotificationSetting(key, change.enabled(), now, actor)));
        }
        auditService.record(organizationId, "NOTIFICATION_SETTINGS_UPDATED", "ORGANIZATION", organizationId,
                Map.of("changes", changes.stream().map(c -> c.type() + ":" + c.channel() + "=" + c.enabled()).toList()));
        return organizationSettings(organizationId);
    }

    private void requireOrganization(Long organizationId) {
        accessGuard.requireOrganizationAccess(organizationId);
        if (!organizationRepository.existsById(organizationId)) {
            throw new NotFoundException("Organization", organizationId);
        }
    }

    private Map<TypeChannel, Boolean> organizationSwitches(Long organizationId) {
        if (organizationId == null) {
            return Map.of();
        }
        return settingRepository.findByOrganizationId(organizationId).stream()
                .collect(Collectors.toMap(s -> new TypeChannel(s.getId().getNotificationType(), s.getId().getChannel()),
                        OrganizationNotificationSetting::isEnabled, (a, b) -> b));
    }
}
