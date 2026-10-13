package com.pgms.room;

import com.pgms.common.api.PageResponse;
import com.pgms.room.RoomDtos.AddBedRequest;
import com.pgms.room.RoomDtos.BedResponse;
import com.pgms.room.RoomDtos.ChangeRoomStatusRequest;
import com.pgms.room.RoomDtos.CreateRoomRequest;
import com.pgms.room.RoomDtos.RoomResponse;
import com.pgms.room.RoomDtos.UpdateRoomRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
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
@RequestMapping("/api/v1/rooms")
@Tag(name = "Rooms")
public class RoomController {

    private static final String WRITERS = "hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER')";
    private static final String READERS = "hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER','STAFF')";

    private final RoomService roomService;
    private final BedService bedService;

    public RoomController(RoomService roomService, BedService bedService) {
        this.roomService = roomService;
        this.bedService = bedService;
    }

    @PostMapping
    @PreAuthorize(WRITERS)
    @Operation(summary = "Create a space (room, hall, kitchen ...) in a property, optionally placed on the floor grid; "
            + "beds are generated from the capacity")
    public ResponseEntity<RoomResponse> create(@RequestParam Long propertyId, @Valid @RequestBody CreateRoomRequest request) {
        RoomResponse created = roomService.create(propertyId, request);
        return ResponseEntity.created(URI.create("/api/v1/rooms/" + created.id())).body(created);
    }

    @GetMapping
    @PreAuthorize(READERS)
    public PageResponse<RoomResponse> search(@RequestParam(required = false) Long propertyId,
                                             @RequestParam(required = false) RoomType roomType,
                                             @RequestParam(required = false) RoomStatus status,
                                             @RequestParam(required = false) Boolean airConditioned,
                                             @RequestParam(required = false) SpaceKind spaceKind,
                                             Pageable pageable) {
        return roomService.search(propertyId, roomType, status, airConditioned, spaceKind, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize(READERS)
    public RoomResponse get(@PathVariable Long id) {
        return roomService.get(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize(WRITERS)
    @Operation(summary = "Update layout/pricing; rent changes affect new allocations only")
    public RoomResponse update(@PathVariable Long id, @Valid @RequestBody UpdateRoomRequest request) {
        return roomService.update(id, request);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize(WRITERS)
    public RoomResponse changeStatus(@PathVariable Long id, @Valid @RequestBody ChangeRoomStatusRequest request) {
        return roomService.changeStatus(id, request.status());
    }

    @PostMapping("/{id}/beds")
    @PreAuthorize(WRITERS)
    @Operation(summary = "Add one bed to a ROOM/HALL/DORMITORY; capacity follows the active bed count")
    public ResponseEntity<BedResponse> addBed(@PathVariable Long id, @Valid @RequestBody AddBedRequest request) {
        BedResponse created = bedService.addBed(id, request);
        return ResponseEntity.created(URI.create("/api/v1/beds/" + created.id())).body(created);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(WRITERS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        roomService.delete(id);
    }
}
