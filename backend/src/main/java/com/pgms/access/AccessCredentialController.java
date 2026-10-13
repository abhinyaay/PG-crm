package com.pgms.access;

import com.pgms.access.AccessDtos.CreateCredentialRequest;
import com.pgms.access.AccessDtos.CredentialResponse;
import com.pgms.access.AccessDtos.NextPinResponse;
import com.pgms.common.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/access/credentials")
@Tag(name = "Access control")
public class AccessCredentialController {

    private final AccessCredentialService service;

    public AccessCredentialController(AccessCredentialService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(AccessSettingsController.MANAGERS)
    public PageResponse<CredentialResponse> list(@RequestParam(required = false) Long propertyId,
                                                 @RequestParam(required = false) Long tenantId,
                                                 @RequestParam(required = false) CredentialStatus status, Pageable pageable) {
        return service.list(propertyId, tenantId, status, pageable);
    }

    @GetMapping("/next-pin")
    @PreAuthorize(AccessSettingsController.MANAGERS)
    @Operation(summary = "Suggested 'Fingerprint ID' (device PIN) for the next enrolment; PINs are never reused")
    public NextPinResponse nextPin(@RequestParam Long propertyId) {
        return service.nextPin(propertyId);
    }

    @PostMapping
    @PreAuthorize(AccessSettingsController.MANAGERS)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Map a device PIN to a resident or staff member (PIN auto-assigned when omitted)")
    public CredentialResponse create(@Valid @RequestBody CreateCredentialRequest request) {
        return service.create(request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(AccessSettingsController.MANAGERS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke a mapping (the PIN then shows as UNKNOWN_CREDENTIAL)")
    public void revoke(@PathVariable Long id) {
        service.revoke(id);
    }
}
