package com.pgms.billing;

import com.pgms.audit.AuditService;
import com.pgms.billing.BillingDtos.IdempotentResult;
import com.pgms.billing.BillingDtos.PaymentResponse;
import com.pgms.billing.BillingDtos.RecordPaymentRequest;
import com.pgms.billing.BillingDtos.RefundRequest;
import com.pgms.billing.BillingDtos.RefundResponse;
import com.pgms.common.exception.BusinessRuleException;
import com.pgms.common.exception.NotFoundException;
import com.pgms.common.lookup.DisplayNames;
import com.pgms.common.util.ReferenceNumberGenerator;
import com.pgms.security.AccessGuard;
import com.pgms.security.CurrentUserProvider;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional core of payment and refund recording. Callers go through {@link PaymentService}, which also handles
 * the rare idempotency race that can only be resolved after this transaction has rolled back.
 */
@Component
class PaymentProcessor {

    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final ReferenceNumberGenerator referenceNumbers;
    private final AccessGuard accessGuard;
    private final CurrentUserProvider currentUserProvider;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final MeterRegistry meterRegistry;
    private final DisplayNames displayNames;
    private final Clock clock;

    PaymentProcessor(InvoiceRepository invoiceRepository, PaymentRepository paymentRepository,
                     RefundRepository refundRepository, ReferenceNumberGenerator referenceNumbers, AccessGuard accessGuard,
                     CurrentUserProvider currentUserProvider, AuditService auditService, ApplicationEventPublisher events,
                     MeterRegistry meterRegistry, DisplayNames displayNames, Clock clock) {
        this.invoiceRepository = invoiceRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.referenceNumbers = referenceNumbers;
        this.accessGuard = accessGuard;
        this.currentUserProvider = currentUserProvider;
        this.auditService = auditService;
        this.events = events;
        this.meterRegistry = meterRegistry;
        this.displayNames = displayNames;
        this.clock = clock;
    }

    /**
     * 1. Lock the invoice row (all payments for an invoice serialise here).
     * 2. Under the lock, look up the idempotency key: a committed earlier attempt is now visible -> replay it.
     * 3. Otherwise validate (not void/paid, no overpayment), apply, insert the payment and flush so constraint
     *    violations surface inside this transaction.
     */
    @Transactional
    public IdempotentResult<PaymentResponse> recordPayment(String idempotencyKey, String fingerprint,
                                                           RecordPaymentRequest request) {
        Invoice invoice = invoiceRepository.findByIdForUpdate(request.invoiceId())
                .orElseThrow(() -> new NotFoundException("Invoice", request.invoiceId()));
        accessGuard.requirePropertyAccess(invoice.getPropertyId());
        return record(invoice, idempotencyKey, fingerprint, request);
    }

    /**
     * Same as {@link #recordPayment} without the caller access check, for server-side flows that have no user or have
     * authorised the caller themselves (AutoPay webhooks/jobs, verified payment claims).
     */
    @Transactional
    public IdempotentResult<PaymentResponse> recordTrustedPayment(String idempotencyKey, String fingerprint,
                                                                  RecordPaymentRequest request) {
        Invoice invoice = invoiceRepository.findByIdForUpdate(request.invoiceId())
                .orElseThrow(() -> new NotFoundException("Invoice", request.invoiceId()));
        return record(invoice, idempotencyKey, fingerprint, request);
    }

    private IdempotentResult<PaymentResponse> record(Invoice invoice, String idempotencyKey, String fingerprint,
                                                     RecordPaymentRequest request) {
        Optional<IdempotentResult<PaymentResponse>> replay = findPaymentReplay(invoice.getOrganizationId(), idempotencyKey,
                fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }

        invoice.applyPayment(request.amount(), LocalDate.now(clock));
        Payment payment = paymentRepository.saveAndFlush(new Payment(
                referenceNumbers.next(ReferenceNumberGenerator.Kind.RECEIPT), invoice, idempotencyKey, fingerprint,
                request.amount(), request.method(), blankToNull(request.externalReference()),
                request.paidAt() == null ? clock.instant() : request.paidAt(), request.notes()));

        auditService.record(invoice.getOrganizationId(), "PAYMENT_RECORDED", "PAYMENT", payment.getId(), Map.of(
                "invoiceId", invoice.getId(), "amount", request.amount(), "method", request.method().name()));
        events.publishEvent(new BillingEvents.PaymentReceived(payment.getId(), payment.getReceiptNumber(),
                payment.getOrganizationId(), payment.getTenantId(), invoice.getInvoiceNumber(), payment.getAmount(),
                invoice.balance()));
        meterRegistry.counter("pgms.payments.recorded", "method", request.method().name()).increment();
        meterRegistry.summary("pgms.payments.amount").record(request.amount().doubleValue());
        return new IdempotentResult<>(PaymentResponse.of(payment, tenantName(payment)), false);
    }

    @Transactional
    public IdempotentResult<RefundResponse> recordRefund(Long paymentId, String idempotencyKey, String fingerprint,
                                                         RefundRequest request) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment", paymentId));
        accessGuard.requirePropertyAccess(payment.getPropertyId());

        Optional<IdempotentResult<RefundResponse>> replay = findRefundReplay(payment.getOrganizationId(), idempotencyKey,
                fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }

        payment.refund(request.amount());
        Refund refund = refundRepository.saveAndFlush(new Refund(referenceNumbers.next(ReferenceNumberGenerator.Kind.REFUND),
                payment, request.amount(), request.reason(), idempotencyKey, fingerprint, clock.instant(),
                currentUserProvider.currentUserId().orElse(null)));
        auditService.record(payment.getOrganizationId(), "REFUND_ISSUED", "PAYMENT", paymentId,
                Map.of("refundId", refund.getId(), "amount", request.amount(), "reason", request.reason()));
        events.publishEvent(new BillingEvents.RefundIssued(refund.getId(), refund.getRefundReference(),
                payment.getOrganizationId(), payment.getTenantId(), request.amount()));
        meterRegistry.counter("pgms.refunds.issued").increment();
        return new IdempotentResult<>(RefundResponse.of(refund), false);
    }

    @Transactional(readOnly = true)
    public Optional<IdempotentResult<PaymentResponse>> findPaymentReplay(Long organizationId, String key, String fingerprint) {
        return paymentRepository.findByOrganizationIdAndIdempotencyKey(organizationId, key).map(existing -> {
            requireSameRequest(existing.getRequestFingerprint(), fingerprint);
            return new IdempotentResult<>(PaymentResponse.of(existing, tenantName(existing)), true);
        });
    }

    @Transactional(readOnly = true)
    public Optional<IdempotentResult<RefundResponse>> findRefundReplay(Long organizationId, String key, String fingerprint) {
        return refundRepository.findByOrganizationIdAndIdempotencyKey(organizationId, key).map(existing -> {
            requireSameRequest(existing.getRequestFingerprint(), fingerprint);
            return new IdempotentResult<>(RefundResponse.of(existing), true);
        });
    }

    private String tenantName(Payment payment) {
        return displayNames.tenants(List.of(payment.getTenantId())).tenant(payment.getTenantId());
    }

    @Transactional(readOnly = true)
    public Long organizationOfInvoice(Long invoiceId) {
        return invoiceRepository.findById(invoiceId).map(Invoice::getOrganizationId)
                .orElseThrow(() -> new NotFoundException("Invoice", invoiceId));
    }

    @Transactional(readOnly = true)
    public Long organizationOfPayment(Long paymentId) {
        return paymentRepository.findById(paymentId).map(Payment::getOrganizationId)
                .orElseThrow(() -> new NotFoundException("Payment", paymentId));
    }

    private static void requireSameRequest(String storedFingerprint, String fingerprint) {
        if (!storedFingerprint.equals(fingerprint)) {
            throw new BusinessRuleException("IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used for a different request");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
