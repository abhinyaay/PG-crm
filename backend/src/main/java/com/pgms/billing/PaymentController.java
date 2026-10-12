package com.pgms.billing;

import com.pgms.billing.BillingDtos.IdempotentResult;
import com.pgms.billing.BillingDtos.PaymentResponse;
import com.pgms.billing.BillingDtos.RecordPaymentRequest;
import com.pgms.billing.BillingDtos.RefundRequest;
import com.pgms.billing.BillingDtos.RefundResponse;
import com.pgms.common.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments")
public class PaymentController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final PaymentService paymentService;
    private final ReceiptService receiptService;

    public PaymentController(PaymentService paymentService, ReceiptService receiptService) {
        this.paymentService = paymentService;
        this.receiptService = receiptService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER')")
    @Operation(summary = "Record a payment against an invoice (idempotent: retry with the same Idempotency-Key)")
    public ResponseEntity<PaymentResponse> record(
            @Parameter(description = "Client-generated unique key (UUID) - identical retries return the original payment")
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @Valid @RequestBody RecordPaymentRequest request) {
        IdempotentResult<PaymentResponse> result = paymentService.recordPayment(idempotencyKey, request);
        return respond(result, URI.create("/api/v1/payments/" + result.body().id()));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER','TENANT')")
    public PageResponse<PaymentResponse> search(@RequestParam(required = false) Long propertyId,
                                                @RequestParam(required = false) Long tenantId,
                                                @RequestParam(required = false) Long invoiceId,
                                                @RequestParam(required = false) PaymentMethod method,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                                Instant from,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                                Instant to,
                                                Pageable pageable) {
        return paymentService.search(propertyId, tenantId, invoiceId, method, from, to, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER','TENANT')")
    public PaymentResponse get(@PathVariable Long id) {
        return paymentService.get(id);
    }

    @GetMapping(value = "/{id}/receipt", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER','TENANT')")
    @Operation(summary = "Download the payment receipt as PDF")
    public ResponseEntity<byte[]> receipt(@PathVariable Long id) {
        ReceiptService.Receipt receipt = receiptService.render(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(receipt.receiptNumber() + ".pdf").build().toString())
                .body(receipt.pdf());
    }

    @PostMapping("/{id}/refunds")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN')")
    @Operation(summary = "Refund (part of) a payment, e.g. the security deposit at move-out. Idempotent.")
    public ResponseEntity<RefundResponse> refund(@PathVariable Long id,
                                                 @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
                                                 @Valid @RequestBody RefundRequest request) {
        IdempotentResult<RefundResponse> result = paymentService.recordRefund(id, idempotencyKey, request);
        return respond(result, URI.create("/api/v1/payments/" + id + "/refunds"));
    }

    @GetMapping("/{id}/refunds")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER','TENANT')")
    public List<RefundResponse> refunds(@PathVariable Long id) {
        return paymentService.refunds(id);
    }

    /** 201 for a newly created resource, 200 + Idempotent-Replayed for a replay of an earlier identical request. */
    private static <T> ResponseEntity<T> respond(IdempotentResult<T> result, URI location) {
        if (result.replayed()) {
            return ResponseEntity.status(HttpStatus.OK).header(REPLAYED_HEADER, "true").body(result.body());
        }
        return ResponseEntity.created(location).body(result.body());
    }
}
