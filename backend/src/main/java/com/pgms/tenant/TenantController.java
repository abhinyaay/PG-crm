package com.pgms.tenant;

import com.pgms.common.api.PageResponse;
import com.pgms.tenant.TenantDtos.AddDocumentRequest;
import com.pgms.tenant.TenantDtos.CreateTenantRequest;
import com.pgms.tenant.TenantDtos.DocumentResponse;
import com.pgms.tenant.TenantDtos.EmergencyContactsRequest;
import com.pgms.tenant.TenantDtos.PortalAccountRequest;
import com.pgms.tenant.TenantDtos.ReviewDocumentRequest;
import com.pgms.tenant.TenantDtos.TenantResponse;
import com.pgms.tenant.TenantDtos.TenantSummary;
import com.pgms.tenant.TenantDtos.UpdateTenantRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants")
@Tag(name = "Tenants")
public class TenantController {

    private static final String OPERATORS = "hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER')";

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @PostMapping
    @PreAuthorize(OPERATORS)
    @Operation(summary = "Register a resident (status PROSPECTIVE until check-in)")
    public ResponseEntity<TenantResponse> create(@Valid @RequestBody CreateTenantRequest request) {
        TenantResponse created = tenantService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/tenants/" + created.id())).body(created);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER','STAFF')")
    @Operation(summary = "Search tenants by name/email/phone with status, property and joining-date filters")
    public PageResponse<TenantSummary> search(@RequestParam(required = false) Long propertyId,
                                              @RequestParam(required = false) TenantStatus status,
                                              @RequestParam(required = false) String q,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                              LocalDate joinedFrom,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                              LocalDate joinedTo,
                                              Pageable pageable) {
        return tenantService.search(propertyId, status, q, joinedFrom, joinedTo, pageable);
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('TENANT')")
    public TenantResponse me() {
        return tenantService.me();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER','STAFF','TENANT')")
    public TenantResponse get(@PathVariable Long id) {
        return tenantService.get(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize(OPERATORS)
    public TenantResponse update(@PathVariable Long id, @Valid @RequestBody UpdateTenantRequest request) {
        return tenantService.update(id, request);
    }

    @PutMapping("/{id}/emergency-contacts")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER','TENANT')")
    public TenantResponse replaceEmergencyContacts(@PathVariable Long id, @Valid @RequestBody EmergencyContactsRequest request) {
        return tenantService.replaceEmergencyContacts(id, request.contacts());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(OPERATORS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        tenantService.delete(id);
    }

    @PostMapping("/{id}/documents")
    @PreAuthorize(OPERATORS)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record ID document metadata; only a masked number is stored")
    public DocumentResponse addDocument(@PathVariable Long id, @Valid @RequestBody AddDocumentRequest request) {
        return tenantService.addDocument(id, request);
    }

    @GetMapping("/{id}/documents")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER','TENANT')")
    public List<DocumentResponse> documents(@PathVariable Long id) {
        return tenantService.documents(id);
    }

    @PostMapping("/{id}/documents/{documentId}/review")
    @PreAuthorize(OPERATORS)
    public DocumentResponse reviewDocument(@PathVariable Long id, @PathVariable Long documentId,
                                           @Valid @RequestBody ReviewDocumentRequest request) {
        return tenantService.reviewDocument(id, documentId, request.status());
    }

    @PostMapping("/{id}/portal-account")
    @PreAuthorize(OPERATORS)
    @Operation(summary = "Create a self-service TENANT login; without initialPassword the resident gets a "
            + "\"set your password\" code by email and SMS")
    public TenantResponse createPortalAccount(@PathVariable Long id,
                                              @Valid @RequestBody(required = false) PortalAccountRequest request) {
        return tenantService.createPortalAccount(id, request == null ? null : request.initialPassword());
    }
}
