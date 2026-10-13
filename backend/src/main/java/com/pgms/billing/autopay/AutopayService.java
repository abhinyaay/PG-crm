package com.pgms.billing.autopay;

import com.pgms.audit.AuditService;
import com.pgms.billing.autopay.AutopayDtos.AttemptResponse;
import com.pgms.billing.autopay.AutopayDtos.MandateResponse;
import com.pgms.billing.autopay.AutopayDtos.StartMandateRequest;
import com.pgms.billing.autopay.AutopayDtos.WebhookAck;
import com.pgms.common.api.PageResponse;
import com.pgms.common.api.SortValidator;
import com.pgms.common.exception.BusinessRuleException;
import com.pgms.common.exception.ConflictException;
import com.pgms.common.exception.ConstraintViolationTranslator;
import com.pgms.common.exception.NotFoundException;
import com.pgms.common.exception.UnauthorizedException;
import com.pgms.security.AccessGuard;
import com.pgms.security.AccessScope;
import com.pgms.tenant.Tenant;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resident AutoPay actions, operator listings, the sandbox authorisation page and provider webhooks. */
@Service
public class AutopayService {

    private static final Logger log = LoggerFactory.getLogger(AutopayService.class);
    private static final Set<String> MANDATE_SORTABLE = Set.of("createdAt", "status");
    private static final Set<String> ATTEMPT_SORTABLE = Set.of("createdAt", "attemptDate", "amount");
    private static final List<MandateStatus> LIVE = List.of(MandateStatus.PENDING_AUTHORISATION, MandateStatus.ACTIVE,
            MandateStatus.PAUSED);

    private final PaymentMandateRepository mandateRepository;
    private final AutopayAttemptRepository attemptRepository;
    private final AutopayEventProcessor processor;
    private final RecurringPaymentGateway gateway;
    private final AccessGuard accessGuard;
    private final AuditService auditService;
    private final Clock clock;

    public AutopayService(PaymentMandateRepository mandateRepository, AutopayAttemptRepository attemptRepository,
                          AutopayEventProcessor processor, RecurringPaymentGateway gateway, AccessGuard accessGuard,
                          AuditService auditService, Clock clock) {
        this.mandateRepository = mandateRepository;
        this.attemptRepository = attemptRepository;
        this.processor = processor;
        this.gateway = gateway;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
        this.clock = clock;
    }

    // ---- resident ----

    /** Registers a mandate with the provider; the resident then approves it at {@code authorisationUrl}. */
    @Transactional
    public MandateResponse start(StartMandateRequest request) {
        Tenant tenant = accessGuard.requireCurrentTenant();
        if (!tenant.isResident()) {
            throw new BusinessRuleException("TENANT_NOT_RESIDENT", "AutoPay can be set up only during a stay");
        }
        if (mandateRepository.existsByTenantIdAndStatusIn(tenant.getId(), LIVE)) {
            throw new ConflictException("MANDATE_EXISTS", "An AutoPay mandate is already set up; cancel it first");
        }
        String reference = "t" + tenant.getId() + "-" + UUID.randomUUID().toString().substring(0, 8);
        RecurringPaymentGateway.MandateSetup setup = gateway.createMandate(new RecurringPaymentGateway.MandateRequest(reference,
                tenant.getFullName(), tenant.getEmail(), tenant.getPhone(), request.method(), request.maxAmount(),
                "Monthly rent AutoPay"));
        PaymentMandate mandate = mandateRepository.saveAndFlush(new PaymentMandate(tenant.getOrganizationId(),
                tenant.getPropertyId(), tenant.getId(), gateway.provider(), setup.providerMandateId(), setup.providerCustomerId(),
                request.method(), request.maxAmount(), setup.authorisationUrl(), request.debitDayOffset()));
        auditService.record(tenant.getOrganizationId(), "AUTOPAY_MANDATE_CREATED", "PAYMENT_MANDATE", mandate.getId(), Map.of(
                "provider", gateway.provider(), "method", request.method().name(), "maxAmount", request.maxAmount()));
        return MandateResponse.of(mandate);
    }

    @Transactional(readOnly = true)
    public List<MandateResponse> mine() {
        Tenant tenant = accessGuard.requireCurrentTenant();
        return mandateRepository.findByTenantIdOrderByIdDesc(tenant.getId()).stream().map(MandateResponse::of).toList();
    }

    @Transactional
    public MandateResponse pause(Long id) {
        PaymentMandate mandate = lockOwn(id);
        if (mandate.pause(clock.instant())) {
            gateway.pauseMandate(mandate);
            processor.mandateChanged(mandate, "tenant");
        }
        return MandateResponse.of(mandate);
    }

    @Transactional
    public MandateResponse resume(Long id) {
        PaymentMandate mandate = lockOwn(id);
        if (mandate.resume()) {
            gateway.resumeMandate(mandate);
            processor.mandateChanged(mandate, "tenant");
        }
        return MandateResponse.of(mandate);
    }

    @Transactional
    public MandateResponse cancel(Long id, String reason) {
        PaymentMandate mandate = lockOwn(id);
        if (mandate.cancel(reason == null || reason.isBlank() ? "Cancelled by resident" : reason.trim(), clock.instant())) {
            gateway.cancelMandate(mandate);
            processor.mandateChanged(mandate, "tenant");
        }
        return MandateResponse.of(mandate);
    }

    /** Sandbox only: the resident approves or rejects the mandate on the simulated bank page. */
    @Transactional
    public MandateResponse sandboxAuthorise(Long id, boolean approve) {
        if (!SandboxRecurringPaymentGateway.NAME.equals(gateway.provider())) {
            throw new NotFoundException("Sandbox mandate", id);
        }
        PaymentMandate mandate = lockOwn(id);
        processor.apply(mandate.getProvider(), approve
                ? GatewayEvent.mandate(GatewayEvent.Type.MANDATE_ACTIVATED, mandate.getProviderMandateId(), null, null)
                : GatewayEvent.mandate(GatewayEvent.Type.MANDATE_FAILED, mandate.getProviderMandateId(), null,
                        "Rejected on the authorisation page"));
        return MandateResponse.of(mandate);
    }

    // ---- operators ----

    @Transactional(readOnly = true)
    public PageResponse<MandateResponse> searchMandates(Long propertyId, MandateStatus status, Long tenantId, Pageable pageable) {
        Pageable page = SortValidator.validate(pageable, MANDATE_SORTABLE, Sort.by(Sort.Direction.DESC, "createdAt"));
        AccessScope scope = accessGuard.scopeFor(propertyId);
        if (scope.isEmpty()) {
            return PageResponse.from(Page.<PaymentMandate>empty(page), MandateResponse::of);
        }
        Specification<PaymentMandate> spec = scope.toSpecification();
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        if (tenantId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("tenantId"), tenantId));
        }
        return PageResponse.from(mandateRepository.findAll(spec, page), MandateResponse::of);
    }

    @Transactional(readOnly = true)
    public PageResponse<AttemptResponse> searchAttempts(Long propertyId, Long mandateId, Long invoiceId, AttemptStatus status,
                                                        Pageable pageable) {
        Pageable page = SortValidator.validate(pageable, ATTEMPT_SORTABLE, Sort.by(Sort.Direction.DESC, "createdAt"));
        Specification<AutopayAttempt> spec;
        if (accessGuard.isTenant()) {
            Long ownId = accessGuard.requireCurrentTenant().getId();
            spec = (root, q, cb) -> cb.equal(root.get("tenantId"), ownId);
        } else {
            AccessScope scope = accessGuard.scopeFor(propertyId);
            if (scope.isEmpty()) {
                return PageResponse.from(Page.<AutopayAttempt>empty(page), AttemptResponse::of);
            }
            spec = scope.toSpecification();
        }
        if (mandateId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("mandateId"), mandateId));
        }
        if (invoiceId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("invoiceId"), invoiceId));
        }
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        return PageResponse.from(attemptRepository.findAll(spec, page), AttemptResponse::of);
    }

    // ---- provider webhooks (public, signature-verified, idempotent) ----

    /**
     * Not transactional itself: the delivery is processed in {@link AutopayEventProcessor#processWebhook}; a concurrent
     * duplicate delivery loses the race on {@code uk_payment_webhook_events} and is acknowledged as a duplicate.
     */
    public WebhookAck handleWebhook(String provider, byte[] rawBody, Map<String, String> headers) {
        if (!gateway.provider().equals(provider)) {
            throw new NotFoundException("Webhook endpoint", provider);
        }
        if (!gateway.verifyWebhookSignature(rawBody, headers)) {
            log.warn("Rejected {} webhook with an invalid signature", provider);
            throw new UnauthorizedException("INVALID_SIGNATURE", "Webhook signature verification failed");
        }
        RecurringPaymentGateway.WebhookPayload payload = gateway.parseWebhook(rawBody, headers);
        try {
            boolean processed = processor.processWebhook(provider, payload);
            return new WebhookAck(processed ? "processed" : "duplicate", payload.eventId());
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolationTranslator.isConstraint(e, "uk_payment_webhook_events")) {
                return new WebhookAck("duplicate", payload.eventId());
            }
            throw e;
        }
    }

    private PaymentMandate lockOwn(Long id) {
        Tenant tenant = accessGuard.requireCurrentTenant();
        return mandateRepository.findByIdForUpdate(id).filter(m -> m.getTenantId().equals(tenant.getId()))
                .orElseThrow(() -> new NotFoundException("Mandate", id));
    }
}
