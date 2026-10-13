package com.pgms.access;

import com.pgms.access.AccessDtos.AccessSettingsRequest;
import com.pgms.access.AccessDtos.AccessSettingsResponse;
import com.pgms.access.AccessDtos.MemberLimitRequest;
import com.pgms.access.AccessDtos.MemberLimitResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Module settings per property and the per-resident daily entry limit. */
@RestController
@Tag(name = "Access control")
public class AccessSettingsController {

    static final String ADMINS = "hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN')";
    static final String MANAGERS = "hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER')";

    private final AccessSettingsService service;

    public AccessSettingsController(AccessSettingsService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/properties/{id}/access-settings")
    @PreAuthorize(MANAGERS)
    @Operation(summary = "Biometric module settings of a property (defaults when never configured: disabled)")
    public AccessSettingsResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PutMapping("/api/v1/properties/{id}/access-settings")
    @PreAuthorize(ADMINS)
    @Operation(summary = "Enable/disable the module, exit mode, default daily entry limit (null = unlimited)")
    public AccessSettingsResponse update(@PathVariable Long id, @Valid @RequestBody AccessSettingsRequest request) {
        return service.update(id, request);
    }

    @GetMapping("/api/v1/tenants/{id}/access-limit")
    @PreAuthorize(MANAGERS)
    public MemberLimitResponse getMemberLimit(@PathVariable Long id) {
        return service.getMemberLimit(id);
    }

    @PutMapping("/api/v1/tenants/{id}/access-limit")
    @PreAuthorize(MANAGERS)
    @Operation(summary = "Per-resident daily entry limit: a number, unlimited=true, or null to use the property default")
    public MemberLimitResponse setMemberLimit(@PathVariable Long id, @Valid @RequestBody MemberLimitRequest request) {
        return service.setMemberLimit(id, request);
    }
}
