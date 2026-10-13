package com.pgms.billing;

import com.pgms.billing.BillingDtos.InvoiceResponse;
import com.pgms.billing.CollectionsDtos.BillingPolicyResponse;
import com.pgms.billing.CollectionsDtos.JobRunResult;
import com.pgms.billing.CollectionsDtos.LateFeeHistoryResponse;
import com.pgms.billing.CollectionsDtos.UpdateBillingPolicyRequest;
import com.pgms.billing.CollectionsDtos.WaiveLateFeeRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Collections: billing policy & late fees")
public class CollectionsController {

    private static final String OPERATORS = "hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER')";

    private final BillingPolicyService policyService;
    private final LateFeeService lateFeeService;
    private final CollectionsJobs collectionsJobs;

    public CollectionsController(BillingPolicyService policyService, LateFeeService lateFeeService,
                                 CollectionsJobs collectionsJobs) {
        this.policyService = policyService;
        this.lateFeeService = lateFeeService;
        this.collectionsJobs = collectionsJobs;
    }

    @GetMapping("/properties/{id}/billing-policy")
    @PreAuthorize(OPERATORS)
    @Operation(summary = "Effective collections policy of a property (late fee, reminders, UPI payee); defaults if not configured")
    public BillingPolicyResponse getPolicy(@PathVariable Long id) {
        return policyService.get(id);
    }

    @PutMapping("/properties/{id}/billing-policy")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN')")
    @Operation(summary = "Create or replace the collections policy (optimistic: send the version you read, null to create)")
    public BillingPolicyResponse updatePolicy(@PathVariable Long id, @Valid @RequestBody UpdateBillingPolicyRequest request) {
        return policyService.update(id, request);
    }

    @GetMapping("/invoices/{id}/late-fees")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER','TENANT')")
    @Operation(summary = "Late-fee charges and waivers of an invoice with totals; tenants only for their own invoices")
    public LateFeeHistoryResponse lateFees(@PathVariable Long id) {
        return lateFeeService.history(id);
    }

    @PostMapping("/invoices/{id}/late-fees/waive")
    @PreAuthorize(OPERATORS)
    @Operation(summary = "Waive (part of) the late fees charged on an invoice (adds a DISCOUNT line, audited)")
    public InvoiceResponse waive(@PathVariable Long id, @Valid @RequestBody WaiveLateFeeRequest request) {
        return lateFeeService.waive(id, request);
    }

    @PostMapping("/billing/late-fees/run")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Run the late-fee job now for a date (idempotent; normally scheduled daily at 01:15)")
    public JobRunResult runLateFees(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return collectionsJobs.runLateFees(date);
    }
}
