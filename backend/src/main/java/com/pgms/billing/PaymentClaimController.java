package com.pgms.billing;

import com.pgms.billing.CollectionsDtos.ApproveClaimRequest;
import com.pgms.billing.CollectionsDtos.ClaimApprovalResponse;
import com.pgms.billing.CollectionsDtos.PayLinkResponse;
import com.pgms.billing.CollectionsDtos.PaymentClaimResponse;
import com.pgms.billing.CollectionsDtos.RejectClaimRequest;
import com.pgms.billing.CollectionsDtos.SubmitClaimRequest;
import com.pgms.common.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
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
@Tag(name = "Collections: UPI pay link & payment claims")
public class PaymentClaimController {

    private static final String OPERATORS = "hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER')";
    private static final String OPERATORS_AND_TENANT = "hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER','TENANT')";

    private final PaymentClaimService claimService;

    public PaymentClaimController(PaymentClaimService claimService) {
        this.claimService = claimService;
    }

    @GetMapping("/invoices/{id}/pay-link")
    @PreAuthorize(OPERATORS_AND_TENANT)
    @Operation(summary = "UPI deep link (also the QR payload) for the invoice balance or a part of it")
    public PayLinkResponse payLink(@PathVariable Long id, @RequestParam(required = false) BigDecimal amount) {
        return claimService.payLink(id, amount);
    }

    @PostMapping("/invoices/{id}/payment-claims")
    @PreAuthorize("hasRole('TENANT')")
    @Operation(summary = "Tenant reports a payment made outside the system (UPI / bank transfer / cash / cheque)")
    public ResponseEntity<PaymentClaimResponse> submit(@PathVariable Long id, @Valid @RequestBody SubmitClaimRequest request) {
        PaymentClaimResponse created = claimService.submit(id, request);
        return ResponseEntity.created(URI.create("/api/v1/payment-claims/" + created.id())).body(created);
    }

    @GetMapping("/payment-claims")
    @PreAuthorize(OPERATORS_AND_TENANT)
    @Operation(summary = "Claims verification queue (scoped); tenants see their own claims")
    public PageResponse<PaymentClaimResponse> search(@RequestParam(required = false) Long propertyId,
                                                     @RequestParam(required = false) PaymentClaimStatus status,
                                                     @RequestParam(required = false) Long tenantId,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                                     LocalDate from,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                                     LocalDate to,
                                                     Pageable pageable) {
        return claimService.search(propertyId, status, tenantId, from, to, pageable);
    }

    @GetMapping("/payment-claims/{id}")
    @PreAuthorize(OPERATORS_AND_TENANT)
    public PaymentClaimResponse get(@PathVariable Long id) {
        return claimService.get(id);
    }

    @PostMapping("/payment-claims/{id}/approve")
    @PreAuthorize(OPERATORS)
    @Operation(summary = "Verify a claim: records the payment (optionally a smaller amount) and marks the claim APPROVED")
    public ClaimApprovalResponse approve(@PathVariable Long id, @Valid @RequestBody(required = false) ApproveClaimRequest request) {
        return claimService.approve(id, request == null ? new ApproveClaimRequest(null, null, null) : request);
    }

    @PostMapping("/payment-claims/{id}/reject")
    @PreAuthorize(OPERATORS)
    public PaymentClaimResponse reject(@PathVariable Long id, @Valid @RequestBody RejectClaimRequest request) {
        return claimService.reject(id, request.reason());
    }

    @PostMapping("/payment-claims/{id}/cancel")
    @PreAuthorize("hasRole('TENANT')")
    public PaymentClaimResponse cancel(@PathVariable Long id) {
        return claimService.cancel(id);
    }
}
