package com.pgms.billing;

import com.pgms.audit.AuditService;
import com.pgms.billing.CollectionsDtos.BillingPolicyResponse;
import com.pgms.billing.CollectionsDtos.LateFeeSettings;
import com.pgms.billing.CollectionsDtos.UpdateBillingPolicyRequest;
import com.pgms.billing.CollectionsDtos.UpiSettings;
import com.pgms.common.exception.BadRequestException;
import com.pgms.common.exception.ConflictException;
import com.pgms.common.exception.NotFoundException;
import com.pgms.config.AppProperties;
import com.pgms.property.PropertyRepository;
import com.pgms.security.AccessGuard;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads and maintains the per-property collections policy; properties without a row get the configured defaults. */
@Service
public class BillingPolicyService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final PropertyBillingPolicyRepository repository;
    private final PropertyRepository propertyRepository;
    private final AccessGuard accessGuard;
    private final AuditService auditService;
    private final CollectionsProperties.Defaults defaults;
    private final int defaultDueReminderDaysBefore;

    public BillingPolicyService(PropertyBillingPolicyRepository repository, PropertyRepository propertyRepository,
                                AccessGuard accessGuard, AuditService auditService, CollectionsProperties collections,
                                AppProperties properties) {
        this.repository = repository;
        this.propertyRepository = propertyRepository;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
        this.defaults = collections.defaults();
        this.defaultDueReminderDaysBefore = properties.billing().dueReminderDaysBefore();
    }

    /** The stored policy or the defaults. No access check: used by jobs and by services that already checked access. */
    @Transactional(readOnly = true)
    public BillingPolicy effectiveFor(Long propertyId) {
        return repository.findById(propertyId).map(PropertyBillingPolicy::toPolicy).orElseGet(() -> defaultsFor(propertyId));
    }

    /** Effective policies of several properties at once (jobs). */
    @Transactional(readOnly = true)
    public Map<Long, BillingPolicy> effectiveFor(Collection<Long> propertyIds) {
        Map<Long, BillingPolicy> result = new HashMap<>();
        repository.findByPropertyIdIn(propertyIds).forEach(p -> result.put(p.getPropertyId(), p.toPolicy()));
        propertyIds.forEach(id -> result.computeIfAbsent(id, this::defaultsFor));
        return result;
    }

    public boolean lateFeesEnabledByDefault() {
        return defaults.lateFeeEnabled();
    }

    @Transactional(readOnly = true)
    public BillingPolicyResponse get(Long propertyId) {
        accessGuard.requirePropertyAccess(propertyId);
        return BillingPolicyResponse.of(effectiveFor(propertyId));
    }

    @Transactional
    public BillingPolicyResponse update(Long propertyId, UpdateBillingPolicyRequest request) {
        accessGuard.requirePropertyAccess(propertyId);
        Long organizationId = propertyRepository.findOrganizationIdById(propertyId)
                .orElseThrow(() -> new NotFoundException("Property", propertyId));
        BillingPolicy requested = validate(propertyId, request);

        Optional<PropertyBillingPolicy> existing = repository.findById(propertyId);
        long expectedVersion = request.version() == null ? 0 : request.version();
        if (existing.isPresent() ? existing.get().getVersion() != expectedVersion || request.version() == null
                : expectedVersion != 0) {
            throw new ConflictException("CONCURRENT_MODIFICATION",
                    "The billing policy was changed by someone else. Reload it and try again.");
        }
        BillingPolicy before = existing.map(PropertyBillingPolicy::toPolicy).orElseGet(() -> defaultsFor(propertyId));
        PropertyBillingPolicy entity = existing.orElseGet(() -> new PropertyBillingPolicy(propertyId, organizationId));
        entity.apply(requested);
        PropertyBillingPolicy saved = repository.saveAndFlush(entity);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("before", snapshot(before));
        details.put("after", snapshot(saved.toPolicy()));
        auditService.record(organizationId, "PROPERTY_POLICY_UPDATED", "PROPERTY", propertyId, details);
        return BillingPolicyResponse.of(saved.toPolicy());
    }

    private BillingPolicy validate(Long propertyId, UpdateBillingPolicyRequest request) {
        LateFeeSettings lateFee = request.lateFee();
        if (lateFee.type() == LateFeeType.PERCENT_OF_RENT && lateFee.value().compareTo(HUNDRED) > 0) {
            throw new BadRequestException("INVALID_LATE_FEE", "A percentage late fee cannot exceed 100");
        }
        if (lateFee.enabled() && lateFee.value().signum() <= 0) {
            throw new BadRequestException("INVALID_LATE_FEE", "An enabled late fee needs a value greater than 0");
        }
        UpiSettings upi = request.upi() == null ? new UpiSettings(null, null) : request.upi();
        String vpa = blankToNull(upi.vpa());
        String payee = blankToNull(upi.payeeName());
        if (payee != null && vpa == null) {
            throw new BadRequestException("INVALID_UPI", "A payee name needs a UPI id (vpa)");
        }
        return new BillingPolicy(propertyId, true, lateFee.enabled(), lateFee.type(), lateFee.value(), lateFee.cap(),
                lateFee.graceDays(), lateFee.moveInGraceDays() == null ? defaults.moveInGraceDays() : lateFee.moveInGraceDays(),
                lateFee.onMoveIn(), request.reminders().dueDaysBefore(),
                ReminderSchedule.normalizeOffsets(request.reminders().overdueOffsets()), vpa, payee, 0);
    }

    private BillingPolicy defaultsFor(Long propertyId) {
        return new BillingPolicy(propertyId, false, defaults.lateFeeEnabled(), defaults.lateFeeType(), defaults.lateFeeValue(),
                defaults.lateFeeCap(), defaults.graceDays(), defaults.moveInGraceDays(), defaults.lateFeeOnMoveIn(),
                defaultDueReminderDaysBefore, defaults.overdueReminderOffsets(), null, null, 0);
    }

    private static Map<String, Object> snapshot(BillingPolicy p) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("configured", p.configured());
        map.put("lateFeeEnabled", p.lateFeeEnabled());
        map.put("lateFeeType", p.lateFeeType().name());
        map.put("lateFeeValue", p.lateFeeValue());
        map.put("lateFeeCap", Objects.toString(p.lateFeeCap(), null));
        map.put("graceDays", p.graceDays());
        map.put("moveInGraceDays", p.moveInGraceDays());
        map.put("lateFeeOnMoveIn", p.lateFeeOnMoveIn());
        map.put("dueReminderDaysBefore", p.dueReminderDaysBefore());
        map.put("overdueReminderOffsets", p.overdueReminderOffsets());
        map.put("upiVpa", p.upiVpa());
        map.put("upiPayeeName", p.upiPayeeName());
        return map;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
