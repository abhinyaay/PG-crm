package com.pgms.access;

import com.pgms.access.AccessDtos.AccessEventResponse;
import com.pgms.access.AccessDtos.ExitRequest;
import com.pgms.access.AccessDtos.ManualEntryRequest;
import com.pgms.access.AccessDtos.MyAccessResponse;
import com.pgms.access.AccessDtos.PresenceResponse;
import com.pgms.common.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/access")
@Tag(name = "Access control")
public class AccessController {

    private static final String OPERATORS = "hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER','STAFF')";
    private static final String OPERATORS_AND_TENANT = "hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER','STAFF','TENANT')";

    private final AccessService service;

    public AccessController(AccessService service) {
        this.service = service;
    }

    @GetMapping("/events")
    @PreAuthorize(OPERATORS_AND_TENANT)
    @Operation(summary = "Gate log (operators: scoped to their properties; residents: their own events)")
    public PageResponse<AccessEventResponse> events(@RequestParam(required = false) Long propertyId,
                                                    @RequestParam(required = false) Long tenantId,
                                                    @RequestParam(required = false) AccessDirection direction,
                                                    @RequestParam(required = false) AccessResult result,
                                                    @RequestParam(required = false) AccessMethod method,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                                    LocalDate from,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                                    LocalDate to,
                                                    Pageable pageable) {
        return service.events(propertyId, tenantId, direction, result, method, from, to, pageable);
    }

    @GetMapping("/presence")
    @PreAuthorize(OPERATORS)
    @Operation(summary = "Who is inside now, since when")
    public PresenceResponse presence(@RequestParam Long propertyId) {
        return service.presence(propertyId);
    }

    @PostMapping("/exit")
    @PreAuthorize(OPERATORS_AND_TENANT)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "\"I'm leaving\" button (resident: no body; staff: {tenantId}) when the exit mode allows it")
    public AccessEventResponse exit(@RequestBody(required = false) ExitRequest request) {
        return service.exit(request);
    }

    @PostMapping("/manual-entry")
    @PreAuthorize("hasAnyRole('PROPERTY_ADMIN','MANAGER')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Audited manual override entry with a reason (e.g. device broken)")
    public AccessEventResponse manualEntry(@Valid @RequestBody ManualEntryRequest request) {
        return service.manualEntry(request);
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('TENANT')")
    @Operation(summary = "Resident's status: IN/OUT, entries today, remaining entries, exit button availability")
    public MyAccessResponse me() {
        return service.me();
    }
}
