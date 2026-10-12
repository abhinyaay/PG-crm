package com.pgms.maintenance;

import com.pgms.common.api.PageResponse;
import com.pgms.common.domain.Priority;
import com.pgms.maintenance.MaintenanceDtos.AssignRequest;
import com.pgms.maintenance.MaintenanceDtos.CreateMaintenanceRequest;
import com.pgms.maintenance.MaintenanceDtos.HistoryEntry;
import com.pgms.maintenance.MaintenanceDtos.MaintenanceResponse;
import com.pgms.maintenance.MaintenanceDtos.StatusChangeRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.data.domain.Pageable;
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
@RequestMapping("/api/v1/maintenance")
@Tag(name = "Maintenance")
public class MaintenanceController {

    private final MaintenanceService service;

    public MaintenanceController(MaintenanceService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Raise a maintenance request (tenants: property/room derived from their allocation)")
    public ResponseEntity<MaintenanceResponse> create(@Valid @RequestBody CreateMaintenanceRequest request) {
        MaintenanceResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/maintenance/" + created.id())).body(created);
    }

    @GetMapping
    public PageResponse<MaintenanceResponse> search(@RequestParam(required = false) Long propertyId,
                                                    @RequestParam(required = false) MaintenanceStatus status,
                                                    @RequestParam(required = false) Priority priority,
                                                    @RequestParam(required = false) MaintenanceCategory category,
                                                    @RequestParam(required = false) Long assigneeUserId,
                                                    @RequestParam(required = false) Boolean slaBreached,
                                                    @RequestParam(defaultValue = "false") boolean assignedToMe,
                                                    Pageable pageable) {
        return service.search(propertyId, status, priority, category, assigneeUserId, slaBreached, assignedToMe, pageable);
    }

    @GetMapping("/{id}")
    public MaintenanceResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @GetMapping("/{id}/history")
    public List<HistoryEntry> history(@PathVariable Long id) {
        return service.history(id);
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER')")
    public MaintenanceResponse assign(@PathVariable Long id, @Valid @RequestBody AssignRequest request) {
        return service.assign(id, request.assigneeUserId());
    }

    @PostMapping("/{id}/status")
    @Operation(summary = "Move the ticket through OPEN -> ASSIGNED -> IN_PROGRESS -> RESOLVED -> CLOSED")
    public MaintenanceResponse changeStatus(@PathVariable Long id, @Valid @RequestBody StatusChangeRequest request) {
        return service.changeStatus(id, request.status(), request.notes());
    }
}
