package com.pgms.property;

import com.pgms.common.api.PageResponse;
import com.pgms.property.PropertyDtos.AssignUserRequest;
import com.pgms.property.PropertyDtos.AssignmentResponse;
import com.pgms.property.PropertyDtos.BuildingResponse;
import com.pgms.property.PropertyDtos.ChangePropertyStatusRequest;
import com.pgms.property.PropertyDtos.CreateBuildingRequest;
import com.pgms.property.PropertyDtos.CreateFloorRequest;
import com.pgms.property.PropertyDtos.CreatePropertyRequest;
import com.pgms.property.PropertyDtos.FloorResponse;
import com.pgms.property.PropertyDtos.OccupancyResponse;
import com.pgms.property.PropertyDtos.PropertyResponse;
import com.pgms.property.PropertyDtos.UpdatePropertyRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/properties")
@Tag(name = "Properties")
public class PropertyController {

    private static final String ADMINS = "hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN')";
    private static final String OPERATORS = "hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER','STAFF')";

    private final PropertyService propertyService;

    public PropertyController(PropertyService propertyService) {
        this.propertyService = propertyService;
    }

    @PostMapping
    @PreAuthorize(ADMINS)
    public ResponseEntity<PropertyResponse> create(@Valid @RequestBody CreatePropertyRequest request) {
        PropertyResponse created = propertyService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/properties/" + created.id())).body(created);
    }

    @GetMapping
    @PreAuthorize(OPERATORS)
    @Operation(summary = "List properties visible to the caller (org-wide for admins, assigned ones for managers/staff)")
    public PageResponse<PropertyResponse> search(@RequestParam(required = false) PropertyStatus status,
                                                 @RequestParam(required = false) String city,
                                                 @RequestParam(required = false) String q,
                                                 Pageable pageable) {
        return propertyService.search(status, city, q, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize(OPERATORS)
    public PropertyResponse get(@PathVariable Long id) {
        return propertyService.get(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize(ADMINS)
    public PropertyResponse update(@PathVariable Long id, @Valid @RequestBody UpdatePropertyRequest request) {
        return propertyService.update(id, request);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize(ADMINS)
    public PropertyResponse changeStatus(@PathVariable Long id, @Valid @RequestBody ChangePropertyStatusRequest request) {
        return propertyService.changeStatus(id, request.status());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(ADMINS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        propertyService.delete(id);
    }

    @GetMapping("/{id}/occupancy")
    @PreAuthorize(OPERATORS)
    @Operation(summary = "Live bed occupancy of a property")
    public OccupancyResponse occupancy(@PathVariable Long id) {
        return propertyService.occupancy(id);
    }

    @PostMapping("/{id}/buildings")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER')")
    @ResponseStatus(HttpStatus.CREATED)
    public BuildingResponse addBuilding(@PathVariable Long id, @Valid @RequestBody CreateBuildingRequest request) {
        return propertyService.addBuilding(id, request);
    }

    @GetMapping("/{id}/buildings")
    @PreAuthorize(OPERATORS)
    public List<BuildingResponse> buildings(@PathVariable Long id) {
        return propertyService.buildings(id);
    }

    @PostMapping("/{id}/buildings/{buildingId}/floors")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER')")
    @ResponseStatus(HttpStatus.CREATED)
    public FloorResponse addFloor(@PathVariable Long id, @PathVariable Long buildingId,
                                  @Valid @RequestBody CreateFloorRequest request) {
        return propertyService.addFloor(id, buildingId, request);
    }

    @GetMapping("/{id}/buildings/{buildingId}/floors")
    @PreAuthorize(OPERATORS)
    public List<FloorResponse> floors(@PathVariable Long id, @PathVariable Long buildingId) {
        return propertyService.floors(id, buildingId);
    }

    @PostMapping("/{id}/assignments")
    @PreAuthorize(ADMINS)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Assign a MANAGER or STAFF user to the property")
    public AssignmentResponse assign(@PathVariable Long id, @Valid @RequestBody AssignUserRequest request) {
        return propertyService.assign(id, request.userId());
    }

    @DeleteMapping("/{id}/assignments/{userId}")
    @PreAuthorize(ADMINS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unassign(@PathVariable Long id, @PathVariable Long userId) {
        propertyService.unassign(id, userId);
    }

    @GetMapping("/{id}/assignments")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER')")
    public List<AssignmentResponse> assignments(@PathVariable Long id) {
        return propertyService.assignments(id);
    }
}
