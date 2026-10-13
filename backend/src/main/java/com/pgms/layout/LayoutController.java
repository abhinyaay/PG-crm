package com.pgms.layout;

import com.pgms.layout.LayoutDtos.FloorLayoutResponse;
import com.pgms.layout.LayoutDtos.UpdateFloorRequest;
import com.pgms.layout.LayoutDtos.UpdateLayoutRequest;
import com.pgms.property.PropertyDtos.FloorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Floor layout")
public class LayoutController {

    private static final String WRITERS = "hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER')";
    private static final String READERS = "hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER','STAFF')";

    private final LayoutService layoutService;

    public LayoutController(LayoutService layoutService) {
        this.layoutService = layoutService;
    }

    @GetMapping("/properties/{id}/floors/{floorId}/layout")
    @PreAuthorize(READERS)
    @Operation(summary = "Floor grid with every space (geometry, kind, prices) and its beds (status, position, overrides)")
    public FloorLayoutResponse get(@PathVariable Long id, @PathVariable Long floorId) {
        return layoutService.get(id, floorId);
    }

    @PutMapping("/properties/{id}/floors/{floorId}/layout")
    @PreAuthorize(WRITERS)
    @Operation(summary = "Bulk move/resize/unplace spaces in one transaction (optimistic version per space; "
            + "422 LAYOUT_OVERLAP / LAYOUT_OUT_OF_BOUNDS, 409 LAYOUT_STALE)")
    public FloorLayoutResponse update(@PathVariable Long id, @PathVariable Long floorId,
                                      @Valid @RequestBody UpdateLayoutRequest request) {
        return layoutService.update(id, floorId, request);
    }

    @PatchMapping("/floors/{floorId}")
    @PreAuthorize(WRITERS)
    @Operation(summary = "Change a floor's label and/or layout grid size")
    public FloorResponse updateFloor(@PathVariable Long floorId, @Valid @RequestBody UpdateFloorRequest request) {
        return layoutService.updateFloor(floorId, request);
    }
}
