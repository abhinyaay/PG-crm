package com.pgms.deposit;

import com.pgms.deposit.DepositDtos.ActionResult;
import com.pgms.deposit.DepositDtos.DeductionRequest;
import com.pgms.deposit.DepositDtos.DepositActionResponse;
import com.pgms.deposit.DepositDtos.DepositListResponse;
import com.pgms.deposit.DepositDtos.ForfeitRequest;
import com.pgms.deposit.DepositDtos.RefundDepositRequest;
import com.pgms.deposit.DepositDtos.TenantDepositsResponse;
import com.pgms.deposit.DepositDtos.TopUpRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/deposits")
@Tag(name = "Deposits")
public class DepositController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String REPLAYED_HEADER = "Idempotent-Replayed";
    private static final String OWNERS = "hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN')";

    private final DepositService depositService;

    public DepositController(DepositService depositService) {
        this.depositService = depositService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER')")
    @Operation(summary = "Deposits per stay with totals (held, refund due, overdue refunds). Sort: refundDueBy, "
            + "moveOutDate, refundDue, createdAt, tenantName, status")
    public DepositListResponse list(@RequestParam(required = false) Long propertyId,
                                    @RequestParam(required = false) DepositStatus status,
                                    @RequestParam(required = false) Long tenantId,
                                    @RequestParam(required = false) Boolean overdue,
                                    Pageable pageable) {
        return depositService.list(propertyId, status, tenantId, overdue, pageable);
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('TENANT')")
    @Operation(summary = "The calling resident's deposits (one per stay) with ledger and refund status")
    public TenantDepositsResponse mine() {
        return depositService.myLedger();
    }

    @GetMapping("/{tenantId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER','TENANT')")
    @Operation(summary = "Deposit ledger of a resident: collected payments, dues adjusted, deductions, refunds")
    public TenantDepositsResponse ledger(@PathVariable Long tenantId) {
        return depositService.ledger(tenantId);
    }

    @PostMapping("/accounts/{accountId}/refund")
    @PreAuthorize(OWNERS)
    @Operation(summary = "Pay out (part of) the refund due. Idempotent: Idempotency-Key header required")
    public ResponseEntity<DepositActionResponse> refund(@PathVariable Long accountId,
                                                        @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
                                                        @Valid @RequestBody RefundDepositRequest request) {
        return respond(depositService.refund(accountId, key, request));
    }

    @PostMapping("/accounts/{accountId}/deductions")
    @PreAuthorize(OWNERS)
    @Operation(summary = "Deduct from the deposit; posted as a SETTLEMENT invoice line (revenue) paid from the deposit")
    public ResponseEntity<DepositActionResponse> deduct(@PathVariable Long accountId,
                                                        @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
                                                        @Valid @RequestBody DeductionRequest request) {
        return respond(depositService.deduct(accountId, key, request));
    }

    @PostMapping("/accounts/{accountId}/forfeit")
    @PreAuthorize(OWNERS)
    @Operation(summary = "Keep what is left of the deposit (after move-out), with a reason")
    public ResponseEntity<DepositActionResponse> forfeit(@PathVariable Long accountId,
                                                         @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
                                                         @Valid @RequestBody ForfeitRequest request) {
        return respond(depositService.forfeit(accountId, key, request));
    }

    @PostMapping("/accounts/{accountId}/top-up")
    @PreAuthorize(OWNERS)
    @Operation(summary = "Request a deposit top-up: issues a deposit invoice for the difference")
    public ResponseEntity<DepositActionResponse> topUp(@PathVariable Long accountId,
                                                       @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
                                                       @Valid @RequestBody TopUpRequest request) {
        return respond(depositService.topUp(accountId, key, request));
    }

    /** 201 for a new action, 200 + Idempotent-Replayed for a replay of an earlier identical request. */
    private static ResponseEntity<DepositActionResponse> respond(ActionResult result) {
        if (result.replayed()) {
            return ResponseEntity.ok().header(REPLAYED_HEADER, "true").body(result.body());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.body());
    }
}
