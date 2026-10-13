package com.pgms.billing;

import com.pgms.common.exception.NotFoundException;
import com.pgms.common.util.ReferenceNumberGenerator;
import com.pgms.security.CurrentUserProvider;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Money movements initiated by the system (move-out settlement, Deposits tab) rather than typed in by a cashier.
 * Every movement is keyed: a repeated call with the same key returns the original rows instead of moving money twice.
 * Callers hold the resident's row lock, so movements for one resident never interleave.
 *
 * <p>An <em>adjustment</em> applies money already received (the security deposit, or rent paid for days not stayed)
 * to another open invoice: a non-cash {@link RefundKind#ADJUSTMENT} refund on the source payment plus a
 * {@link PaymentMethod#DEPOSIT_ADJUSTMENT} payment on the target invoice. "Deposit held" (deposit payments net of
 * refunds) and "outstanding" (open invoice balances) therefore stay consistent with what was settled.
 */
@Component
public class InternalMoneyMovements {

    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final ReferenceNumberGenerator referenceNumbers;
    private final CurrentUserProvider currentUserProvider;
    private final Clock clock;

    public InternalMoneyMovements(InvoiceRepository invoiceRepository, PaymentRepository paymentRepository,
                                  RefundRepository refundRepository, ReferenceNumberGenerator referenceNumbers,
                                  CurrentUserProvider currentUserProvider, Clock clock) {
        this.invoiceRepository = invoiceRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.referenceNumbers = referenceNumbers;
        this.currentUserProvider = currentUserProvider;
        this.clock = clock;
    }

    /** Result of one adjustment slice. */
    public record Adjustment(Long paymentId, String receiptNumber, Long refundId, BigDecimal amount, boolean replayed) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Adjustment adjust(Long sourcePaymentId, Long targetInvoiceId, BigDecimal amount, String key, String reason,
                             Long depositTransactionId) {
        Invoice invoice = invoiceRepository.findByIdForUpdate(targetInvoiceId)
                .orElseThrow(() -> new NotFoundException("Invoice", targetInvoiceId));
        Payment source = paymentRepository.findByIdForUpdate(sourcePaymentId)
                .orElseThrow(() -> new NotFoundException("Payment", sourcePaymentId));
        String paymentKey = key + "-p";
        Optional<Payment> existing = paymentRepository.findByOrganizationIdAndIdempotencyKey(invoice.getOrganizationId(),
                paymentKey);
        if (existing.isPresent()) {
            Long refundId = refundRepository.findByOrganizationIdAndIdempotencyKey(invoice.getOrganizationId(), key + "-r")
                    .map(Refund::getId).orElse(null);
            return new Adjustment(existing.get().getId(), existing.get().getReceiptNumber(), refundId,
                    existing.get().getAmount(), true);
        }
        Instant now = clock.instant();
        Long userId = currentUserProvider.currentUserId().orElse(null);
        source.refund(amount);
        Refund refund = refundRepository.save(new Refund(referenceNumbers.next(ReferenceNumberGenerator.Kind.REFUND), source,
                amount, truncate("Adjusted against " + invoice.getInvoiceNumber() + ": " + reason), key + "-r", now, userId,
                RefundKind.ADJUSTMENT, null, null, depositTransactionId));
        invoice.applyPayment(amount, LocalDate.now(clock));
        Payment payment = paymentRepository.saveAndFlush(new Payment(referenceNumbers.next(ReferenceNumberGenerator.Kind.RECEIPT),
                invoice, paymentKey, "internal", amount, PaymentMethod.DEPOSIT_ADJUSTMENT, null, now, truncate(reason)));
        return new Adjustment(payment.getId(), payment.getReceiptNumber(), refund.getId(), amount, false);
    }

    /** Pays (part of) a payment back to the resident in cash/UPI/bank transfer/cheque. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Refund payout(Long paymentId, BigDecimal amount, String key, String reason, PaymentMethod method,
                         String payoutReference, Long depositTransactionId) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment", paymentId));
        Optional<Refund> existing = refundRepository.findByOrganizationIdAndIdempotencyKey(payment.getOrganizationId(), key);
        if (existing.isPresent()) {
            return existing.get();
        }
        payment.refund(amount);
        return refundRepository.saveAndFlush(new Refund(referenceNumbers.next(ReferenceNumberGenerator.Kind.REFUND), payment,
                amount, truncate(reason), key, clock.instant(), currentUserProvider.currentUserId().orElse(null),
                RefundKind.CASH, method, payoutReference, depositTransactionId));
    }

    private static String truncate(String text) {
        return text == null || text.length() <= 255 ? text : text.substring(0, 255);
    }
}
