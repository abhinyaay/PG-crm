package com.pgms.room;

import com.pgms.common.api.PageResponse;
import com.pgms.room.RoomDtos.BedResponse;
import com.pgms.room.RoomDtos.ChangeBedStatusRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/beds")
@Tag(name = "Beds")
public class BedController {

    private final RoomService roomService;

    public BedController(RoomService roomService) {
        this.roomService = roomService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER','STAFF')")
    @Operation(summary = "Search beds, e.g. availability: ?propertyId=1&status=AVAILABLE&roomType=DOUBLE&maxRent=9000")
    public PageResponse<BedResponse> search(@RequestParam(required = false) Long propertyId,
                                            @RequestParam(required = false) Long roomId,
                                            @RequestParam(required = false) BedStatus status,
                                            @RequestParam(required = false) RoomType roomType,
                                            @RequestParam(required = false) Boolean airConditioned,
                                            @RequestParam(required = false) BigDecimal maxRent,
                                            Pageable pageable) {
        return roomService.searchBeds(propertyId, roomId, status, roomType, airConditioned, maxRent, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER','STAFF')")
    public BedResponse get(@PathVariable Long id) {
        return roomService.getBed(id);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER')")
    @Operation(summary = "Toggle a bed between AVAILABLE and MAINTENANCE")
    public BedResponse changeStatus(@PathVariable Long id, @Valid @RequestBody ChangeBedStatusRequest request) {
        return roomService.changeBedStatus(id, request.status());
    }
}
