package com.pgms.billing;

import com.pgms.billing.CollectionsDtos.CreateRecurringChargeRequest;
import com.pgms.billing.CollectionsDtos.RecurringChargeResponse;
import com.pgms.billing.CollectionsDtos.UpdateRecurringChargeRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Collections: recurring charges")
public class RecurringChargeController {

    private static final String EDITORS = "hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER')";

    private final RecurringChargeService service;

    public RecurringChargeController(RecurringChargeService service) {
        this.service = service;
    }

    @GetMapping("/tenants/{tenantId}/recurring-charges")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER','TENANT')")
    @Operation(summary = "Monthly add-ons of a resident (tenants: their own)")
    public List<RecurringChargeResponse> list(@PathVariable Long tenantId) {
        return service.list(tenantId);
    }

    @PostMapping("/tenants/{tenantId}/recurring-charges")
    @PreAuthorize(EDITORS)
    @Operation(summary = "Add a monthly add-on; it is copied into every rent invoice from startMonth to endMonth")
    public ResponseEntity<RecurringChargeResponse> create(@PathVariable Long tenantId,
                                                          @Valid @RequestBody CreateRecurringChargeRequest request) {
        RecurringChargeResponse created = service.create(tenantId, request);
        return ResponseEntity.created(URI.create("/api/v1/recurring-charges/" + created.id())).body(created);
    }

    @PutMapping("/recurring-charges/{id}")
    @PreAuthorize(EDITORS)
    @Operation(summary = "Change a monthly add-on (future invoices only; issued invoices keep their lines)")
    public RecurringChargeResponse update(@PathVariable Long id, @Valid @RequestBody UpdateRecurringChargeRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/recurring-charges/{id}")
    @PreAuthorize(EDITORS)
    @Operation(summary = "End a monthly add-on (kept for history)")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
