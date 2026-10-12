package com.pgms.billing;

import com.pgms.billing.BillingDtos.AddChargeRequest;
import com.pgms.billing.BillingDtos.DiscountRequest;
import com.pgms.billing.BillingDtos.GenerationResult;
import com.pgms.billing.BillingDtos.InvoiceResponse;
import com.pgms.billing.BillingDtos.TenantBalanceResponse;
import com.pgms.billing.BillingDtos.VoidInvoiceRequest;
import com.pgms.common.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.YearMonth;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
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
@Tag(name = "Invoices & Rent")
public class InvoiceController {

    private static final String OPERATORS = "hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER')";

    private final InvoiceService invoiceService;
    private final BillingJobs billingJobs;

    public InvoiceController(InvoiceService invoiceService, BillingJobs billingJobs) {
        this.invoiceService = invoiceService;
        this.billingJobs = billingJobs;
    }

    @GetMapping("/invoices")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER','TENANT')")
    @Operation(summary = "Search invoices; tenants only see their own")
    public PageResponse<InvoiceResponse> search(@RequestParam(required = false) Long propertyId,
                                                @RequestParam(required = false) Long tenantId,
                                                @RequestParam(required = false) InvoiceStatus status,
                                                @RequestParam(required = false) InvoiceType type,
                                                @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM")
                                                YearMonth billingMonth,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                                LocalDate dueBefore,
                                                Pageable pageable) {
        return invoiceService.search(propertyId, tenantId, status, type, billingMonth, dueBefore, pageable);
    }

    @GetMapping("/invoices/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER','TENANT')")
    public InvoiceResponse get(@PathVariable Long id) {
        return invoiceService.get(id);
    }

    @PostMapping("/invoices/{id}/charges")
    @PreAuthorize(OPERATORS)
    @Operation(summary = "Add an additional charge (electricity, laundry, late fee ...) to an invoice")
    public InvoiceResponse addCharge(@PathVariable Long id, @Valid @RequestBody AddChargeRequest request) {
        return invoiceService.addCharge(id, request);
    }

    @PostMapping("/invoices/{id}/discounts")
    @PreAuthorize(OPERATORS)
    public InvoiceResponse applyDiscount(@PathVariable Long id, @Valid @RequestBody DiscountRequest request) {
        return invoiceService.applyDiscount(id, request.reason(), request.amount());
    }

    @PostMapping("/invoices/{id}/void")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN')")
    public InvoiceResponse voidInvoice(@PathVariable Long id, @Valid @RequestBody VoidInvoiceRequest request) {
        return invoiceService.voidInvoice(id, request.reason());
    }

    @PostMapping("/invoices/generate")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Run monthly rent generation now (idempotent; normally scheduled daily)")
    public GenerationResult generate(@RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return billingJobs.generateRentInvoices(month);
    }

    @GetMapping("/tenants/{tenantId}/balance")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER','TENANT')")
    @Operation(summary = "Outstanding balance, deposit held and open invoices of a tenant")
    public TenantBalanceResponse balance(@PathVariable Long tenantId) {
        return invoiceService.tenantBalance(tenantId);
    }
}
