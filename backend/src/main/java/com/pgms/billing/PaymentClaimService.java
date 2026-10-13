package com.pgms.billing;

import com.pgms.audit.AuditService;
import com.pgms.billing.BillingDtos.IdempotentResult;
import com.pgms.billing.BillingDtos.PaymentResponse;
import com.pgms.billing.BillingDtos.RecordPaymentRequest;
import com.pgms.billing.CollectionsDtos.ApproveClaimRequest;
import com.pgms.billing.CollectionsDtos.ClaimApprovalResponse;
import com.pgms.billing.CollectionsDtos.PayLinkResponse;
import com.pgms.billing.CollectionsDtos.PaymentClaimResponse;
import com.pgms.billing.CollectionsDtos.SubmitClaimRequest;
import com.pgms.common.api.PageResponse;
import com.pgms.common.api.SortValidator;
import com.pgms.common.exception.BusinessRuleException;
import com.pgms.common.exception.ConflictException;
import com.pgms.common.exception.NotFoundException;
import com.pgms.common.util.ReferenceNumberGenerator;
import com.pgms.property.Property;
import com.pgms.property.PropertyRepository;
import com.pgms.security.AccessGuard;
import com.pgms.security.AccessScope;
import com.pgms.security.CurrentUserProvider;
import com.pgms.tenant.Tenant;
import com.pgms.tenant.TenantRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * UPI pay links and the "I've paid" claim workflow: tenant submits (amount, method, UTR, date) → manager verifies →
 * approve records a real payment through the idempotent {@link PaymentProcessor} (key {@code claim:{id}}), or reject
 * with a reason. A UTR can be claimed only once per organization and never for a reference already recorded.
 */
@Service
public class PaymentClaimService {

    private static final Set<String> SORTABLE = Set.of("createdAt", "amount", "paidOn");
    private static final LocalTime CLAIMED_PAYMENT_TIME = LocalTime.NOON;

    private final PaymentClaimRepository claimRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final TenantRepository tenantRepository;
    private final PropertyRepository propertyRepository;
    private final BillingPolicyService policyService;
    private final InvoiceService invoiceService;
    private final PaymentProcessor paymentProcessor;
    private final ReferenceNumberGenerator referenceNumbers;
    private final AccessGuard accessGuard;
    private final CurrentUserProvider currentUserProvider;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public PaymentClaimService(PaymentClaimRepository claimRepository, InvoiceRepository invoiceRepository,
                               PaymentRepository paymentRepository, TenantRepository tenantRepository,
                               PropertyRepository propertyRepository, BillingPolicyService policyService,
                               InvoiceService invoiceService, PaymentProcessor paymentProcessor,
                               ReferenceNumberGenerator referenceNumbers, AccessGuard accessGuard,
                               CurrentUserProvider currentUserProvider, AuditService auditService,
                               ApplicationEventPublisher events, Clock clock) {
        this.claimRepository = claimRepository;
        this.invoiceRepository = invoiceRepository;
        this.paymentRepository = paymentRepository;
        this.tenantRepository = tenantRepository;
        this.propertyRepository = propertyRepository;
        this.policyService = policyService;
        this.invoiceService = invoiceService;
        this.paymentProcessor = paymentProcessor;
        this.referenceNumbers = referenceNumbers;
        this.accessGuard = accessGuard;
        this.currentUserProvider = currentUserProvider;
        this.auditService = auditService;
        this.events = events;
        this.clock = clock;
    }

    // ---- pay link ----

    @Transactional(readOnly = true)
    public PayLinkResponse payLink(Long invoiceId, BigDecimal requestedAmount) {
        Invoice invoice = invoiceRepository.findById(invoiceId).orElseThrow(() -> new NotFoundException("Invoice", invoiceId));
        invoiceService.requireAccess(invoice);
        BillingPolicy policy = policyService.effectiveFor(invoice.getPropertyId());
        if (policy.upiVpa() == null) {
            throw new BusinessRuleException("UPI_NOT_CONFIGURED", "This property has no UPI id configured for payments");
        }
        BigDecimal amount = requirePayable(invoice, (requestedAmount == null ? invoice.balance() : requestedAmount)
                .setScale(2, RoundingMode.HALF_UP));
        String payee = policy.upiPayeeName() != null ? policy.upiPayeeName()
                : propertyRepository.findById(invoice.getPropertyId()).map(Property::getName).orElse("PG");
        String uri = UpiLinks.payUri(policy.upiVpa(), payee, amount, invoice.getInvoiceNumber());
        return new PayLinkResponse(invoice.getId(), invoice.getInvoiceNumber(), amount, invoice.getCurrency(), payee,
                policy.upiVpa(), uri, uri);
    }

    // ---- tenant ----

    @Transactional
    public PaymentClaimResponse submit(Long invoiceId, SubmitClaimRequest request) {
        Tenant tenant = accessGuard.requireCurrentTenant();
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .filter(i -> i.getTenantId().equals(tenant.getId()))
                .orElseThrow(() -> new NotFoundException("Invoice", invoiceId));
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        String utr = UpiLinks.normalizeReference(request.method(), request.utr(), note);
        requirePayable(invoice, request.amount());
        if (utr != null && (claimRepository.existsLiveUtr(invoice.getOrganizationId(), utr)
                || paymentRepository.existsByOrganizationIdAndExternalReferenceIgnoreCase(invoice.getOrganizationId(), utr))) {
            throw new ConflictException("DUPLICATE_TRANSACTION", "This transaction reference was already submitted or recorded");
        }
        Long userId = currentUserProvider.require().id();
        PaymentClaim claim = claimRepository.saveAndFlush(new PaymentClaim(
                referenceNumbers.next(ReferenceNumberGenerator.Kind.PAYMENT_CLAIM), invoice, userId, request.amount(),
                request.method(), utr, request.paidOn(), note));
        auditService.record(invoice.getOrganizationId(), "PAYMENT_CLAIM_SUBMITTED", "PAYMENT_CLAIM", claim.getId(), Map.of(
                "invoiceId", invoiceId, "amount", request.amount(), "method", request.method().name()));
        events.publishEvent(new BillingEvents.PaymentClaimSubmitted(claim.getId(), claim.getClaimReference(),
                claim.getOrganizationId(), claim.getPropertyId(), claim.getTenantId(), tenant.getFullName(),
                invoice.getInvoiceNumber(), claim.getAmount(), claim.getMethod(), utr));
        return PaymentClaimResponse.of(claim, tenant.getFullName(), invoice.getInvoiceNumber());
    }

    @Transactional
    public PaymentClaimResponse cancel(Long claimId) {
        Tenant tenant = accessGuard.requireCurrentTenant();
        PaymentClaim claim = claimRepository.findByIdForUpdate(claimId)
                .filter(c -> c.getTenantId().equals(tenant.getId()))
                .orElseThrow(() -> new NotFoundException("PaymentClaim", claimId));
        claim.cancel(clock.instant());
        auditService.record(claim.getOrganizationId(), "PAYMENT_CLAIM_CANCELLED", "PAYMENT_CLAIM", claimId);
        return toResponse(claim);
    }

    // ---- reading ----

    @Transactional(readOnly = true)
    public PaymentClaimResponse get(Long claimId) {
        PaymentClaim claim = claimRepository.findById(claimId).orElseThrow(() -> new NotFoundException("PaymentClaim", claimId));
        if (accessGuard.isTenant()) {
            if (!claim.getTenantId().equals(accessGuard.requireCurrentTenant().getId())) {
                throw new NotFoundException("PaymentClaim", claimId);
            }
        } else {
            accessGuard.requirePropertyAccess(claim.getPropertyId());
        }
        return toResponse(claim);
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentClaimResponse> search(Long propertyId, PaymentClaimStatus status, Long tenantId, LocalDate from,
                                                     LocalDate to, Pageable pageable) {
        Pageable page = SortValidator.validate(pageable, SORTABLE, Sort.by(Sort.Direction.DESC, "createdAt"));
        Specification<PaymentClaim> spec;
        if (accessGuard.isTenant()) {
            Long ownId = accessGuard.requireCurrentTenant().getId();
            spec = (root, q, cb) -> cb.equal(root.get("tenantId"), ownId);
        } else {
            AccessScope scope = accessGuard.scopeFor(propertyId);
            if (scope.isEmpty()) {
                return PageResponse.from(Page.<PaymentClaim>empty(page), c -> toResponse(c, Map.of(), Map.of()));
            }
            spec = scope.toSpecification();
            if (tenantId != null) {
                spec = spec.and((root, q, cb) -> cb.equal(root.get("tenantId"), tenantId));
            }
        }
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        if (from != null) {
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("paidOn"), from));
        }
        if (to != null) {
            spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("paidOn"), to));
        }
        Page<PaymentClaim> result = claimRepository.findAll(spec, page);
        Map<Long, String> tenantNames = tenantNames(result.getContent().stream().map(PaymentClaim::getTenantId).toList());
        Map<Long, String> invoiceNumbers = invoiceNumbers(result.getContent().stream().map(PaymentClaim::getInvoiceId).toList());
        return PageResponse.from(result, c -> toResponse(c, tenantNames, invoiceNumbers));
    }

    // ---- manager ----

    /**
     * One transaction: lock the claim, record the payment (the processor locks the invoice: claim → invoice), mark the
     * claim APPROVED. A concurrent second approval waits on the claim lock and then fails with CLAIM_NOT_PENDING; the
     * idempotency key {@code claim:{id}} guarantees at most one payment per claim even outside that lock. The existing
     * PaymentReceived event notifies the tenant.
     */
    @Transactional
    public ClaimApprovalResponse approve(Long claimId, ApproveClaimRequest request) {
        PaymentClaim claim = lockAccessible(claimId);
        claim.requirePending();
        BigDecimal amount = request.amount() == null ? claim.getAmount() : request.amount();
        if (amount.compareTo(claim.getAmount()) > 0) {
            throw new BusinessRuleException("APPROVAL_EXCEEDS_CLAIM",
                    "Cannot approve more than the claimed " + claim.getAmount());
        }
        Instant paidAt = request.paidAt() != null ? request.paidAt()
                : claim.getPaidOn().atTime(CLAIMED_PAYMENT_TIME).atZone(clock.getZone()).toInstant();
        String notes = "Verified claim " + claim.getClaimReference()
                + (amount.compareTo(claim.getAmount()) < 0 ? " (claimed " + claim.getAmount() + ")" : "")
                + (request.notes() == null || request.notes().isBlank() ? "" : ": " + request.notes().trim());
        RecordPaymentRequest payment = new RecordPaymentRequest(claim.getInvoiceId(), amount, claim.getMethod(), claim.getUtr(),
                paidAt, truncate(notes, 500));
        IdempotentResult<PaymentResponse> recorded = paymentProcessor.recordTrustedPayment("claim:" + claim.getId(),
                PaymentService.fingerprint(payment), payment);
        Long reviewer = currentUserProvider.currentUserId().orElse(null);
        claim.approve(recorded.body().id(), amount, reviewer, clock.instant(), request.notes());
        claimRepository.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("paymentId", recorded.body().id());
        details.put("claimedAmount", claim.getAmount());
        details.put("approvedAmount", amount);
        auditService.record(claim.getOrganizationId(), "PAYMENT_CLAIM_APPROVED", "PAYMENT_CLAIM", claimId, details);
        return new ClaimApprovalResponse(toResponse(claim), recorded.body());
    }

    @Transactional
    public PaymentClaimResponse reject(Long claimId, String reason) {
        PaymentClaim claim = lockAccessible(claimId);
        String trimmed = reason.trim();
        claim.reject(trimmed, currentUserProvider.currentUserId().orElse(null), clock.instant());
        claimRepository.flush();
        auditService.record(claim.getOrganizationId(), "PAYMENT_CLAIM_REJECTED", "PAYMENT_CLAIM", claimId,
                Map.of("reason", trimmed));
        String invoiceNumber = invoiceNumbers(List.of(claim.getInvoiceId())).get(claim.getInvoiceId());
        events.publishEvent(new BillingEvents.PaymentClaimRejected(claim.getId(), claim.getClaimReference(),
                claim.getOrganizationId(), claim.getPropertyId(), claim.getTenantId(), invoiceNumber, claim.getAmount(),
                claim.getUtr(), trimmed));
        return toResponse(claim);
    }

    // ---- digest ----

    /** One PAYMENT_CLAIMS_PENDING digest per property for claims older than the SLA (own transaction per property). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean sendPendingDigest(Long propertyId, List<Long> claimIds, LocalDate today) {
        List<PaymentClaim> pending = claimRepository.findAllById(claimIds).stream()
                .filter(c -> c.getStatus() == PaymentClaimStatus.SUBMITTED).toList();
        Property property = propertyRepository.findById(propertyId).orElse(null);
        if (pending.isEmpty() || property == null) {
            return false;
        }
        BigDecimal total = pending.stream().map(PaymentClaim::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        events.publishEvent(new BillingEvents.PaymentClaimsPending(propertyId, property.getName(), property.getOrganizationId(),
                today, pending.size(), total));
        return true;
    }

    private PaymentClaim lockAccessible(Long claimId) {
        PaymentClaim claim = claimRepository.findByIdForUpdate(claimId)
                .orElseThrow(() -> new NotFoundException("PaymentClaim", claimId));
        accessGuard.requirePropertyAccess(claim.getPropertyId());
        return claim;
    }

    private static BigDecimal requirePayable(Invoice invoice, BigDecimal amount) {
        if (!invoice.isOpen()) {
            throw new ConflictException("INVOICE_NOT_PAYABLE", "Invoice " + invoice.getInvoiceNumber() + " is " + invoice.getStatus());
        }
        if (amount.signum() <= 0) {
            throw new BusinessRuleException("INVALID_AMOUNT", "Amount must be positive");
        }
        if (amount.compareTo(invoice.balance()) > 0) {
            throw new BusinessRuleException("OVERPAYMENT", "Amount " + amount + " exceeds the outstanding balance " + invoice.balance());
        }
        return amount;
    }

    private PaymentClaimResponse toResponse(PaymentClaim claim) {
        return toResponse(claim, tenantNames(List.of(claim.getTenantId())), invoiceNumbers(List.of(claim.getInvoiceId())));
    }

    private static PaymentClaimResponse toResponse(PaymentClaim claim, Map<Long, String> tenantNames,
                                                   Map<Long, String> invoiceNumbers) {
        return PaymentClaimResponse.of(claim, tenantNames.get(claim.getTenantId()), invoiceNumbers.get(claim.getInvoiceId()));
    }

    private Map<Long, String> tenantNames(Collection<Long> ids) {
        return tenantRepository.findAllById(Set.copyOf(ids)).stream()
                .collect(Collectors.toMap(Tenant::getId, Tenant::getFullName, (a, b) -> a));
    }

    private Map<Long, String> invoiceNumbers(Collection<Long> ids) {
        return invoiceRepository.findAllById(Set.copyOf(ids)).stream()
                .collect(Collectors.toMap(Invoice::getId, Invoice::getInvoiceNumber, (a, b) -> a));
    }

    private static String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }
}
