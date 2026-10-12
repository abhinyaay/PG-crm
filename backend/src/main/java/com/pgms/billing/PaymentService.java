package com.pgms.billing;

import com.pgms.billing.BillingDtos.IdempotentResult;
import com.pgms.billing.BillingDtos.PaymentResponse;
import com.pgms.billing.BillingDtos.RecordPaymentRequest;
import com.pgms.billing.BillingDtos.RefundRequest;
import com.pgms.billing.BillingDtos.RefundResponse;
import com.pgms.common.api.PageResponse;
import com.pgms.common.api.SortValidator;
import com.pgms.common.exception.BadRequestException;
import com.pgms.common.exception.ConstraintViolationTranslator;
import com.pgms.common.exception.NotFoundException;
import com.pgms.common.util.Hashing;
import com.pgms.security.AccessGuard;
import com.pgms.security.AccessScope;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent payment/refund API.
 *
 * <p>Normal retries are resolved inside {@link PaymentProcessor} under the invoice row lock. The remaining race -
 * the same key sent concurrently for two <em>different</em> invoices (so no shared lock) - is caught by the
 * {@code uk_payments_idempotency} unique index. Its violation aborts that transaction, so the replay lookup has to
 * run here, outside it, in a fresh read.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final Pattern KEY_FORMAT = Pattern.compile("^[A-Za-z0-9_-]{8,100}$");
    private static final Set<String> SORTABLE = Set.of("paidAt", "amount", "createdAt");

    private final PaymentProcessor processor;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final AccessGuard accessGuard;

    public PaymentService(PaymentProcessor processor, PaymentRepository paymentRepository, RefundRepository refundRepository,
                          AccessGuard accessGuard) {
        this.processor = processor;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.accessGuard = accessGuard;
    }

    public IdempotentResult<PaymentResponse> recordPayment(String idempotencyKey, RecordPaymentRequest request) {
        validateKey(idempotencyKey);
        String fingerprint = Hashing.sha256Hex(String.join("|", "payment", String.valueOf(request.invoiceId()),
                request.amount().setScale(2, RoundingMode.HALF_UP).toPlainString(), request.method().name(),
                String.valueOf(request.externalReference()), String.valueOf(request.paidAt())));
        try {
            return processor.recordPayment(idempotencyKey, fingerprint, request);
        } catch (DataIntegrityViolationException e) {
            if (!ConstraintViolationTranslator.isConstraint(e, "uk_payments_idempotency")) {
                throw e;
            }
            log.info("Concurrent duplicate payment request detected for key={}", idempotencyKey);
            Long organizationId = processor.organizationOfInvoice(request.invoiceId());
            return processor.findPaymentReplay(organizationId, idempotencyKey, fingerprint).orElseThrow(() -> e);
        }
    }

    public IdempotentResult<RefundResponse> recordRefund(Long paymentId, String idempotencyKey, RefundRequest request) {
        validateKey(idempotencyKey);
        String fingerprint = Hashing.sha256Hex(String.join("|", "refund", String.valueOf(paymentId),
                request.amount().setScale(2, RoundingMode.HALF_UP).toPlainString(), request.reason()));
        try {
            return processor.recordRefund(paymentId, idempotencyKey, fingerprint, request);
        } catch (DataIntegrityViolationException e) {
            if (!ConstraintViolationTranslator.isConstraint(e, "uk_refunds_idempotency")) {
                throw e;
            }
            Long organizationId = processor.organizationOfPayment(paymentId);
            return processor.findRefundReplay(organizationId, idempotencyKey, fingerprint).orElseThrow(() -> e);
        }
    }

    @Transactional(readOnly = true)
    public PaymentResponse get(Long id) {
        return PaymentResponse.of(loadAccessible(id));
    }

    @Transactional(readOnly = true)
    public List<RefundResponse> refunds(Long paymentId) {
        loadAccessible(paymentId);
        return refundRepository.findByPaymentIdOrderByRefundedAt(paymentId).stream().map(RefundResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> search(Long propertyId, Long tenantId, Long invoiceId, PaymentMethod method,
                                                Instant from, Instant to, Pageable pageable) {
        Pageable page = SortValidator.validate(pageable, SORTABLE, Sort.by(Sort.Direction.DESC, "paidAt"));
        Specification<Payment> spec;
        if (accessGuard.isTenant()) {
            Long ownId = accessGuard.requireCurrentTenant().getId();
            spec = (root, q, cb) -> cb.equal(root.get("tenantId"), ownId);
        } else {
            AccessScope scope = accessGuard.scopeFor(propertyId);
            if (scope.isEmpty()) {
                return PageResponse.from(Page.<Payment>empty(page), PaymentResponse::of);
            }
            spec = scope.toSpecification();
            if (tenantId != null) {
                spec = spec.and((root, q, cb) -> cb.equal(root.get("tenantId"), tenantId));
            }
        }
        if (invoiceId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("invoiceId"), invoiceId));
        }
        if (method != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("method"), method));
        }
        if (from != null) {
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("paidAt"), from));
        }
        if (to != null) {
            spec = spec.and((root, q, cb) -> cb.lessThan(root.get("paidAt"), to));
        }
        return PageResponse.from(paymentRepository.findAll(spec, page), PaymentResponse::of);
    }

    @Transactional(readOnly = true)
    public Payment loadAccessible(Long id) {
        Payment payment = paymentRepository.findById(id).orElseThrow(() -> new NotFoundException("Payment", id));
        if (accessGuard.isTenant()) {
            if (!payment.getTenantId().equals(accessGuard.requireCurrentTenant().getId())) {
                throw new NotFoundException("Payment", id);
            }
        } else {
            accessGuard.requirePropertyAccess(payment.getPropertyId());
        }
        return payment;
    }

    private static void validateKey(String key) {
        if (key == null || !KEY_FORMAT.matcher(key).matches()) {
            throw new BadRequestException("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key header must be 8-100 characters of letters, digits, '-' or '_' (a UUID is recommended)");
        }
    }
}
