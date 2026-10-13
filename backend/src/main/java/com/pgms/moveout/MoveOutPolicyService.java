package com.pgms.moveout;

import com.pgms.audit.AuditService;
import com.pgms.common.exception.NotFoundException;
import com.pgms.moveout.MoveOutDtos.PolicyResponse;
import com.pgms.moveout.MoveOutDtos.UpdatePolicyRequest;
import com.pgms.property.PropertyRepository;
import com.pgms.security.AccessGuard;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Per-property notice / lock-in / final-month / refund-SLA rules and the inspection checklist template. */
@Service
public class MoveOutPolicyService {

    private final MoveOutPolicyRepository repository;
    private final PropertyRepository propertyRepository;
    private final AccessGuard accessGuard;
    private final AuditService auditService;

    public MoveOutPolicyService(MoveOutPolicyRepository repository, PropertyRepository propertyRepository,
                                AccessGuard accessGuard, AuditService auditService) {
        this.repository = repository;
        this.propertyRepository = propertyRepository;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
    }

    /** The stored policy or the defaults (no access check: callers have already checked the property). */
    @Transactional(readOnly = true)
    public MoveOutPolicy effective(Long propertyId) {
        return repository.findById(propertyId).orElseGet(() -> MoveOutPolicy.defaults(propertyId,
                propertyRepository.findOrganizationIdById(propertyId)
                        .orElseThrow(() -> new NotFoundException("Property", propertyId))));
    }

    @Transactional(readOnly = true)
    public PolicyResponse get(Long propertyId) {
        accessGuard.requirePropertyAccess(propertyId);
        return repository.findById(propertyId).map(p -> PolicyResponse.of(p, true))
                .orElseGet(() -> PolicyResponse.of(effective(propertyId), false));
    }

    @Transactional
    public PolicyResponse update(Long propertyId, UpdatePolicyRequest request) {
        accessGuard.requirePropertyAccess(propertyId);
        Long organizationId = propertyRepository.findOrganizationIdById(propertyId)
                .orElseThrow(() -> new NotFoundException("Property", propertyId));
        MoveOutPolicy policy = repository.findById(propertyId).orElseGet(() -> new MoveOutPolicy(propertyId, organizationId));
        policy.update(request.noticePeriodDays(), request.noticeShortfallRule(), request.lockInMonths(),
                request.lockInPenaltyRule(), request.lockInPenaltyAmount(), request.finalMonthBilling(),
                request.depositRefundDays(), request.inspectionChecklist().stream().map(String::trim).toList());
        MoveOutPolicy saved = repository.saveAndFlush(policy);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("noticePeriodDays", request.noticePeriodDays());
        details.put("noticeShortfallRule", request.noticeShortfallRule().name());
        details.put("lockInMonths", request.lockInMonths());
        details.put("lockInPenaltyRule", request.lockInPenaltyRule().name());
        details.put("finalMonthBilling", request.finalMonthBilling().name());
        details.put("depositRefundDays", request.depositRefundDays());
        auditService.record(organizationId, "MOVE_OUT_POLICY_UPDATED", "PROPERTY", propertyId, details);
        return PolicyResponse.of(saved, true);
    }
}
