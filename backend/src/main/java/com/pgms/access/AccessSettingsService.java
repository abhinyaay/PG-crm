package com.pgms.access;

import com.pgms.access.AccessDtos.AccessSettingsRequest;
import com.pgms.access.AccessDtos.AccessSettingsResponse;
import com.pgms.access.AccessDtos.MemberLimitRequest;
import com.pgms.access.AccessDtos.MemberLimitResponse;
import com.pgms.audit.AuditService;
import com.pgms.common.exception.NotFoundException;
import com.pgms.property.PropertyRepository;
import com.pgms.security.AccessGuard;
import com.pgms.tenant.Tenant;
import com.pgms.tenant.TenantRepository;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Property-level module settings and per-resident daily entry limits. */
@Service
public class AccessSettingsService {

    private final AccessSettingsRepository settingsRepository;
    private final AccessMemberLimitRepository memberLimitRepository;
    private final PropertyRepository propertyRepository;
    private final TenantRepository tenantRepository;
    private final AccessEventProcessor processor;
    private final AccessGuard accessGuard;
    private final AuditService auditService;
    private final Clock clock;

    public AccessSettingsService(AccessSettingsRepository settingsRepository, AccessMemberLimitRepository memberLimitRepository,
                                 PropertyRepository propertyRepository, TenantRepository tenantRepository,
                                 AccessEventProcessor processor, AccessGuard accessGuard, AuditService auditService, Clock clock) {
        this.settingsRepository = settingsRepository;
        this.memberLimitRepository = memberLimitRepository;
        this.propertyRepository = propertyRepository;
        this.tenantRepository = tenantRepository;
        this.processor = processor;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AccessSettingsResponse get(Long propertyId) {
        accessGuard.requirePropertyAccess(propertyId);
        return settingsRepository.findById(propertyId)
                .map(s -> AccessSettingsResponse.of(s, true))
                .orElseGet(() -> AccessSettingsResponse.of(AccessSettings.defaults(propertyId), false));
    }

    @Transactional
    public AccessSettingsResponse update(Long propertyId, AccessSettingsRequest request) {
        accessGuard.requirePropertyAccess(propertyId);
        Long organizationId = propertyRepository.findOrganizationIdById(propertyId)
                .orElseThrow(() -> new NotFoundException("Property", propertyId));
        Optional<AccessSettings> existing = settingsRepository.findById(propertyId);
        AccessSettings settings = existing.orElseGet(() -> new AccessSettings(propertyId));
        Map<String, Object> before = snapshot(settings);
        settings.update(request.enabled(), request.exitMode(), request.maxEntriesPerDay(),
                request.trackStaff() != null ? request.trackStaff() : settings.isTrackStaff(),
                request.deviceOfflineMinutes() != null ? request.deviceOfflineMinutes() : settings.getDeviceOfflineMinutes());
        settings = settingsRepository.saveAndFlush(settings);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("before", existing.isPresent() ? before : "defaults");
        details.put("after", snapshot(settings));
        auditService.record(organizationId, "ACCESS_SETTINGS_UPDATED", "PROPERTY", propertyId, details);
        return AccessSettingsResponse.of(settings, true);
    }

    // ---- per-resident limit ----

    @Transactional(readOnly = true)
    public MemberLimitResponse getMemberLimit(Long tenantId) {
        Tenant tenant = loadOperatorAccessibleTenant(tenantId);
        return memberLimit(tenant, memberLimitRepository.findById(tenantId));
    }

    @Transactional
    public MemberLimitResponse setMemberLimit(Long tenantId, MemberLimitRequest request) {
        Tenant tenant = loadOperatorAccessibleTenant(tenantId);
        Optional<AccessMemberLimit> existing = memberLimitRepository.findById(tenantId);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("before", existing.map(l -> l.getMaxEntriesPerDay() == null ? "unlimited" : (Object) l.getMaxEntriesPerDay())
                .orElse("property default"));
        Optional<AccessMemberLimit> after;
        if (!request.unlimited() && request.maxEntriesPerDay() == null) {
            existing.ifPresent(memberLimitRepository::delete);
            after = Optional.empty();
            details.put("after", "property default");
        } else {
            AccessMemberLimit limit = existing.orElseGet(() -> new AccessMemberLimit(tenantId));
            limit.set(request.unlimited() ? null : request.maxEntriesPerDay(), clock.instant(), accessGuard.currentUser().id());
            after = Optional.of(memberLimitRepository.save(limit));
            details.put("after", request.unlimited() ? "unlimited" : request.maxEntriesPerDay());
        }
        auditService.record(tenant.getOrganizationId(), "ACCESS_MEMBER_LIMIT_UPDATED", "TENANT", tenantId, details);
        return memberLimit(tenant, after);
    }

    private MemberLimitResponse memberLimit(Tenant tenant, Optional<AccessMemberLimit> override) {
        AccessSettings settings = processor.settingsFor(tenant.getPropertyId());
        Integer value = override.map(AccessMemberLimit::getMaxEntriesPerDay).orElse(null);
        return new MemberLimitResponse(tenant.getId(), override.isPresent(), override.isPresent() && value == null, value,
                settings.getMaxEntriesPerDay(),
                AccessRules.effectiveLimit(override.isPresent(), value, settings.getMaxEntriesPerDay()));
    }

    private Tenant loadOperatorAccessibleTenant(Long tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId).orElseThrow(() -> new NotFoundException("Tenant", tenantId));
        accessGuard.requirePropertyAccess(tenant.getPropertyId());
        return tenant;
    }

    private static Map<String, Object> snapshot(AccessSettings s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", s.isEnabled());
        map.put("exitMode", s.getExitMode().name());
        map.put("maxEntriesPerDay", s.getMaxEntriesPerDay() == null ? "unlimited" : s.getMaxEntriesPerDay());
        map.put("trackStaff", s.isTrackStaff());
        map.put("deviceOfflineMinutes", s.getDeviceOfflineMinutes());
        return map;
    }
}
