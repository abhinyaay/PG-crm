package com.pgms.billing.autopay;

import com.pgms.billing.Invoice;
import com.pgms.billing.InvoiceRepository;
import com.pgms.billing.InvoiceType;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional steps of one debit: (1) create the attempt under the invoice lock, (2) - outside any transaction,
 * in the job - call the provider, (3) record the outcome. Each step is its own REQUIRES_NEW transaction so a slow or
 * failing provider call never holds row locks and one bad invoice never affects the rest of the run.
 */
@Service
public class AutopayDebitService {

    private final InvoiceRepository invoiceRepository;
    private final PaymentMandateRepository mandateRepository;
    private final AutopayAttemptRepository attemptRepository;
    private final AutopayEventProcessor processor;
    private final AutopayProperties properties;
    private final Clock clock;

    public AutopayDebitService(InvoiceRepository invoiceRepository, PaymentMandateRepository mandateRepository,
                               AutopayAttemptRepository attemptRepository, AutopayEventProcessor processor,
                               AutopayProperties properties, Clock clock) {
        this.invoiceRepository = invoiceRepository;
        this.mandateRepository = mandateRepository;
        this.attemptRepository = attemptRepository;
        this.processor = processor;
        this.properties = properties;
        this.clock = clock;
    }

    /** What the job sends to the provider for a newly created attempt. */
    public record PreparedDebit(Long attemptId, String provider, RecurringPaymentGateway.DebitRequest request) {
    }

    /**
     * Creates attempt n+1 when the invoice is due for collection today: open RENT invoice with a balance within the
     * mandate's limit, due date minus the debit offset reached, no attempt yet today, none still awaiting confirmation
     * and fewer than {@code max-attempts} so far. A concurrent duplicate trips the unique keys (caller: "skipped").
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<PreparedDebit> prepare(Long invoiceId, Long mandateId, LocalDate today) {
        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId).orElse(null);
        PaymentMandate mandate = mandateRepository.findById(mandateId).orElse(null);
        if (invoice == null || mandate == null || mandate.getStatus() != MandateStatus.ACTIVE
                || !mandate.getTenantId().equals(invoice.getTenantId()) || invoice.getInvoiceType() != InvoiceType.RENT
                || !invoice.isOpen() || invoice.balance().signum() <= 0
                || invoice.getDueDate().minusDays(mandate.getDebitDayOffset()).isAfter(today)
                || invoice.balance().compareTo(mandate.getMaxAmount()) > 0) {
            return Optional.empty();
        }
        List<AutopayAttempt> previous = attemptRepository.findByInvoiceIdOrderByAttemptNo(invoiceId);
        boolean blocked = previous.size() >= properties.maxAttempts() || previous.stream().anyMatch(a ->
                a.getAttemptDate().equals(today) || a.getStatus() == AttemptStatus.SCHEDULED);
        if (blocked) {
            return Optional.empty();
        }
        if (!mandate.getPropertyId().equals(invoice.getPropertyId())) {
            mandate.relocate(invoice.getPropertyId()); // resident transferred to another property
        }
        AutopayAttempt attempt = attemptRepository.saveAndFlush(new AutopayAttempt(mandate, invoiceId, invoice.getPropertyId(),
                invoice.balance(), previous.size() + 1, today, clock.instant()));
        return Optional.of(new PreparedDebit(attempt.getId(), mandate.getProvider(), new RecurringPaymentGateway.DebitRequest(
                attempt.getId(), mandate.getProviderCustomerId(), mandate.getProviderTokenId(), mandate.getProviderMandateId(),
                attempt.getAmount(), "Rent " + invoice.getInvoiceNumber())));
    }

    /** Applies the provider's immediate answer (PENDING waits for the webhook). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RecurringPaymentGateway.DebitOutcome recordOutcome(PreparedDebit debit, RecurringPaymentGateway.DebitResult result) {
        switch (result.outcome()) {
            case SUCCEEDED -> processor.apply(debit.provider(), GatewayEvent.payment(GatewayEvent.Type.PAYMENT_SUCCEEDED,
                    result.providerPaymentId(), debit.attemptId(), debit.request().amount(), null));
            case FAILED -> processor.apply(debit.provider(), GatewayEvent.payment(GatewayEvent.Type.PAYMENT_FAILED,
                    result.providerPaymentId(), debit.attemptId(), debit.request().amount(), result.failureReason()));
            case PENDING -> attemptRepository.findByIdForUpdate(debit.attemptId())
                    .ifPresent(a -> a.requested(result.providerPaymentId(), clock.instant()));
            default -> throw new IllegalStateException("Unknown outcome " + result.outcome());
        }
        return result.outcome();
    }

    /** The provider call itself failed (network, rejected request): the attempt counts as failed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordError(PreparedDebit debit, String message) {
        processor.apply(debit.provider(), GatewayEvent.payment(GatewayEvent.Type.PAYMENT_FAILED, null, debit.attemptId(),
                debit.request().amount(), message));
    }

    /** A debit the provider never confirmed within the timeout is failed so the next attempt can be made. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expire(Long attemptId) {
        attemptRepository.findById(attemptId).ifPresent(a -> processor.apply(a.getProvider(), GatewayEvent.payment(
                GatewayEvent.Type.PAYMENT_FAILED, null, a.getId(), a.getAmount(), "No confirmation from the provider")));
    }
}
