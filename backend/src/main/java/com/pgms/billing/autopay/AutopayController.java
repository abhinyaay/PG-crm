package com.pgms.billing.autopay;

import com.pgms.billing.autopay.AutopayDtos.AttemptResponse;
import com.pgms.billing.autopay.AutopayDtos.CancelMandateRequest;
import com.pgms.billing.autopay.AutopayDtos.DebitRunResult;
import com.pgms.billing.autopay.AutopayDtos.MandateResponse;
import com.pgms.billing.autopay.AutopayDtos.SandboxAuthoriseRequest;
import com.pgms.billing.autopay.AutopayDtos.StartMandateRequest;
import com.pgms.billing.autopay.AutopayDtos.WebhookAck;
import com.pgms.common.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "AutoPay (recurring payments)")
public class AutopayController {

    private static final String OPERATORS = "hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER')";

    private final AutopayService autopayService;
    private final AutopayJobs autopayJobs;

    public AutopayController(AutopayService autopayService, AutopayJobs autopayJobs) {
        this.autopayService = autopayService;
        this.autopayJobs = autopayJobs;
    }

    @PostMapping("/autopay/mandates")
    @PreAuthorize("hasRole('TENANT')")
    @Operation(summary = "Start AutoPay: registers a mandate and returns the URL where the resident authorises it")
    public ResponseEntity<MandateResponse> start(@Valid @RequestBody StartMandateRequest request) {
        MandateResponse created = autopayService.start(request);
        return ResponseEntity.created(URI.create("/api/v1/autopay/mandates/" + created.id())).body(created);
    }

    @GetMapping("/autopay/mandates/me")
    @PreAuthorize("hasRole('TENANT')")
    @Operation(summary = "The caller's mandates, newest first")
    public List<MandateResponse> mine() {
        return autopayService.mine();
    }

    @PostMapping("/autopay/mandates/{id}/pause")
    @PreAuthorize("hasRole('TENANT')")
    public MandateResponse pause(@PathVariable Long id) {
        return autopayService.pause(id);
    }

    @PostMapping("/autopay/mandates/{id}/resume")
    @PreAuthorize("hasRole('TENANT')")
    public MandateResponse resume(@PathVariable Long id) {
        return autopayService.resume(id);
    }

    @PostMapping("/autopay/mandates/{id}/cancel")
    @PreAuthorize("hasRole('TENANT')")
    public MandateResponse cancel(@PathVariable Long id, @Valid @RequestBody(required = false) CancelMandateRequest request) {
        return autopayService.cancel(id, request == null ? null : request.reason());
    }

    @PostMapping("/autopay/sandbox/mandates/{id}/authorise")
    @PreAuthorize("hasRole('TENANT')")
    @Operation(summary = "Sandbox provider only: approve or reject the mandate on the simulated bank page")
    public MandateResponse sandboxAuthorise(@PathVariable Long id, @RequestBody SandboxAuthoriseRequest request) {
        return autopayService.sandboxAuthorise(id, request.approve());
    }

    @GetMapping("/autopay/mandates")
    @PreAuthorize(OPERATORS)
    @Operation(summary = "Mandates of the properties the caller can see")
    public PageResponse<MandateResponse> mandates(@RequestParam(required = false) Long propertyId,
                                                  @RequestParam(required = false) MandateStatus status,
                                                  @RequestParam(required = false) Long tenantId,
                                                  Pageable pageable) {
        return autopayService.searchMandates(propertyId, status, tenantId, pageable);
    }

    @GetMapping("/autopay/attempts")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER','TENANT')")
    @Operation(summary = "Debit attempts (scoped); tenants see their own")
    public PageResponse<AttemptResponse> attempts(@RequestParam(required = false) Long propertyId,
                                                  @RequestParam(required = false) Long mandateId,
                                                  @RequestParam(required = false) Long invoiceId,
                                                  @RequestParam(required = false) AttemptStatus status,
                                                  Pageable pageable) {
        return autopayService.searchAttempts(propertyId, mandateId, invoiceId, status, pageable);
    }

    @PostMapping("/billing/autopay/run")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Run the AutoPay debit job now for a date (idempotent per invoice per day)")
    public DebitRunResult run(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return autopayJobs.runDebits(date);
    }

    /** Public (no JWT): authenticity comes from the HMAC signature over the raw body. */
    @PostMapping("/payments/webhooks/{provider}")
    @Operation(summary = "Payment provider webhook (signature-verified, idempotent per event id)")
    public WebhookAck webhook(@PathVariable String provider, @RequestBody byte[] body, HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        return autopayService.handleWebhook(provider, body, headers);
    }
}
