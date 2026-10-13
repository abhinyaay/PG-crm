package com.pgms.billing.autopay;

import com.pgms.audit.AuditService;
import com.pgms.billing.BillingDtos.IdempotentResult;
import com.pgms.billing.BillingDtos.PaymentResponse;
import com.pgms.billing.BillingDtos.RecordPaymentRequest;
import com.pgms.billing.Invoice;
import com.pgms.billing.InvoiceRepository;
import com.pgms.billing.PaymentMethod;
import com.pgms.billing.PaymentService;
import com.pgms.common.exception.ConflictException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies provider outcomes ({@link GatewayEvent}) to mandates and debit attempts. Shared by the webhook endpoint,
 * the synchronous sandbox outcomes and the sandbox authorisation page, so every path behaves the same. Every step is
 * idempotent: replayed activations/failures change nothing, and a confirmed debit is recorded through
 * {@link PaymentService} with idempotency key {@code autopay:{provider}:{providerPaymentId}} and method AUTOPAY.
 */
@Service
public class AutopayEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(AutopayEventProcessor.class);

    private final PaymentMandateRepository mandateRepository;
    private final AutopayAttemptRepository attemptRepository;
    private final PaymentWebhookEventRepository webhookEventRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentService paymentService;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final AutopayProperties properties;
    private final Clock clock;

    public AutopayEventProcessor(PaymentMandateRepository mandateRepository, AutopayAttemptRepository attemptRepository,
                                 PaymentWebhookEventRepository webhookEventRepository, InvoiceRepository invoiceRepository,
                                 PaymentService paymentService, AuditService auditService, ApplicationEventPublisher events,
                                 AutopayProperties properties, Clock clock) {
        this.mandateRepository = mandateRepository;
        this.attemptRepository = attemptRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.invoiceRepository = invoiceRepository;
        this.paymentService = paymentService;
        this.auditService = auditService;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Records the delivery and applies its events in one transaction. Returns false for an event id seen before (the
     * caller also treats a concurrent duplicate's unique-key violation as "duplicate").
     */
    @Transactional
    public boolean processWebhook(String provider, RecurringPaymentGateway.WebhookPayload payload) {
        if (webhookEventRepository.existsByProviderAndEventId(provider, payload.eventId())) {
            return false;
        }
        webhookEventRepository.saveAndFlush(new PaymentWebhookEvent(provider, payload.eventId(), payload.eventType(),
                clock.instant()));
        payload.events().forEach(event -> apply(provider, event));
        return true;
    }

    @Transactional
    public void apply(String provider, GatewayEvent event) {
        if (event.isPayment()) {
            applyPayment(provider, event);
        } else {
            applyMandate(provider, event);
        }
    }

    private void applyMandate(String provider, GatewayEvent event) {
        Optional<PaymentMandate> found = event.providerMandateId() != null
                ? mandateRepository.findByProviderMandateIdForUpdate(provider, event.providerMandateId())
                : Optional.empty();
        if (found.isEmpty() && event.providerTokenId() != null) {
            found = mandateRepository.findByProviderTokenIdForUpdate(provider, event.providerTokenId());
        }
        if (found.isEmpty()) {
            log.warn("AutoPay {} event {} for unknown mandate {} / token {}", provider, event.type(), event.providerMandateId(),
                    event.providerTokenId());
            return;
        }
        PaymentMandate mandate = found.get();
        Instant now = clock.instant();
        boolean changed;
        try {
            changed = switch (event.type()) {
                case MANDATE_ACTIVATED -> mandate.activate(event.providerTokenId(), now);
                case MANDATE_FAILED -> mandate.fail(event.reason() == null ? "Authorisation rejected" : event.reason());
                case MANDATE_PAUSED -> mandate.pause(now);
                case MANDATE_RESUMED -> mandate.resume();
                case MANDATE_CANCELLED -> mandate.cancel(event.reason(), now);
                default -> false;
            };
        } catch (ConflictException e) {
            log.warn("Ignoring AutoPay {} for mandate {}: {}", event.type(), mandate.getId(), e.getMessage());
            return;
        }
        if (changed) {
            mandateRepository.flush();
            mandateChanged(mandate, "provider");
        }
    }

    /** Audit + notification after any mandate status change (provider event or resident action). */
    void mandateChanged(PaymentMandate mandate, String source) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", mandate.getStatus().name());
        details.put("source", source);
        if (mandate.getStatusReason() != null) {
            details.put("reason", mandate.getStatusReason());
        }
        auditService.record(mandate.getOrganizationId(), "AUTOPAY_MANDATE_" + mandate.getStatus().name(), "PAYMENT_MANDATE",
                mandate.getId(), details);
        events.publishEvent(new AutopayEvents.MandateStatusChanged(mandate.getId(), mandate.getOrganizationId(),
                mandate.getPropertyId(), mandate.getTenantId(), mandate.getStatus(), mandate.getStatusReason()));
    }

    private void applyPayment(String provider, GatewayEvent event) {
        Optional<AutopayAttempt> found = event.providerPaymentId() != null
                ? attemptRepository.findByProviderPaymentIdForUpdate(provider, event.providerPaymentId())
                : Optional.empty();
        if (found.isEmpty() && event.attemptId() != null) {
            found = attemptRepository.findByIdForUpdate(event.attemptId()).filter(a -> a.getProvider().equals(provider));
        }
        if (found.isEmpty()) {
            log.warn("AutoPay {} event {} for unknown payment {} / attempt {}", provider, event.type(), event.providerPaymentId(),
                    event.attemptId());
            return;
        }
        AutopayAttempt attempt = found.get();
        String providerPaymentId = event.providerPaymentId() != null ? event.providerPaymentId() : attempt.getProviderPaymentId();
        Instant now = clock.instant();
        if (event.type() == GatewayEvent.Type.PAYMENT_FAILED) {
            if (attempt.fail(providerPaymentId, event.reason(), now)) {
                attemptRepository.flush();
                boolean finalAttempt = attempt.getAttemptNo() >= properties.maxAttempts();
                auditService.record(attempt.getOrganizationId(), "AUTOPAY_DEBIT_FAILED", "AUTOPAY_ATTEMPT", attempt.getId(),
                        Map.of("invoiceId", attempt.getInvoiceId(), "attemptNo", attempt.getAttemptNo(),
                                "reason", attempt.getFailureReason()));
                events.publishEvent(new AutopayEvents.DebitFailed(attempt.getId(), attempt.getOrganizationId(),
                        attempt.getPropertyId(), attempt.getTenantId(), invoiceNumber(attempt.getInvoiceId()), attempt.getAmount(),
                        attempt.getFailureReason(), attempt.getAttemptNo(), finalAttempt));
            }
            return;
        }
        if (attempt.getStatus() == AttemptStatus.SUCCEEDED) {
            return; // replayed confirmation
        }
        recordSuccess(attempt, provider, providerPaymentId, now);
    }

    private void recordSuccess(AutopayAttempt attempt, String provider, String providerPaymentId, Instant now) {
        // lock order attempt -> invoice; the balance is read under the same lock the payment will take
        Invoice invoice = invoiceRepository.findByIdForUpdate(attempt.getInvoiceId()).orElseThrow();
        BigDecimal applicable = invoice.isOpen() ? attempt.getAmount().min(invoice.balance()) : BigDecimal.ZERO;
        if (applicable.signum() <= 0) {
            // paid by other means while the debit was in flight: the money must go back through the provider
            attempt.succeed(providerPaymentId, null, "UNAPPLIED: invoice already settled - refund through the provider", now);
            attemptRepository.flush();
            auditService.record(attempt.getOrganizationId(), "AUTOPAY_EXCESS_COLLECTED", "AUTOPAY_ATTEMPT", attempt.getId(),
                    Map.of("invoiceId", attempt.getInvoiceId(), "amount", attempt.getAmount()));
            return;
        }
        String key = "autopay:" + provider + ":" + (providerPaymentId != null ? providerPaymentId : "attempt-" + attempt.getId());
        IdempotentResult<PaymentResponse> payment = paymentService.recordTrustedPayment(key, new RecordPaymentRequest(
                attempt.getInvoiceId(), applicable, PaymentMethod.AUTOPAY, providerPaymentId, null,
                "AutoPay attempt #" + attempt.getAttemptNo()));
        String note = applicable.compareTo(attempt.getAmount()) < 0
                ? "Applied " + applicable + " of " + attempt.getAmount() + "; refund the rest through the provider" : null;
        attempt.succeed(providerPaymentId, payment.body().id(), note, now);
        attemptRepository.flush();
        events.publishEvent(new AutopayEvents.DebitSucceeded(attempt.getId(), attempt.getOrganizationId(), attempt.getPropertyId(),
                attempt.getTenantId(), invoice.getInvoiceNumber(), applicable));
    }

    private String invoiceNumber(Long invoiceId) {
        return invoiceRepository.findById(invoiceId).map(Invoice::getInvoiceNumber).orElse("#" + invoiceId);
    }
}
