package com.pgms.layout;

import com.pgms.audit.AuditService;
import com.pgms.common.exception.BadRequestException;
import com.pgms.common.exception.ConflictException;
import com.pgms.common.exception.NotFoundException;
import com.pgms.layout.LayoutDtos.FloorLayoutResponse;
import com.pgms.layout.LayoutDtos.LayoutSpace;
import com.pgms.layout.LayoutDtos.SpacePlacement;
import com.pgms.layout.LayoutDtos.UpdateFloorRequest;
import com.pgms.layout.LayoutDtos.UpdateLayoutRequest;
import com.pgms.layout.LayoutGeometry.Rect;
import com.pgms.property.Building;
import com.pgms.property.Floor;
import com.pgms.property.FloorRepository;
import com.pgms.property.PropertyDtos.FloorResponse;
import com.pgms.property.PropertyRepository;
import com.pgms.room.Bed;
import com.pgms.room.BedRepository;
import com.pgms.room.Room;
import com.pgms.room.RoomRepository;
import com.pgms.security.AccessGuard;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Floor layout editor. Reads are three queries regardless of floor size (floor + building, spaces, beds).
 *
 * <p>Writes that change geometry lock the floor row first ({@link FloorRepository#findByIdForUpdate}), then validate
 * the <em>resulting</em> floor as a whole (inside the grid, no overlaps), so concurrent editors are serialised and can
 * never commit overlapping spaces. Each moved space also carries the version the client last saw: a space changed in
 * the meantime (moved by someone else, beds added, repriced) is refused with 409 instead of being overwritten.
 */
@Service
public class LayoutService {

    private final FloorRepository floorRepository;
    private final RoomRepository roomRepository;
    private final BedRepository bedRepository;
    private final PropertyRepository propertyRepository;
    private final AccessGuard accessGuard;
    private final AuditService auditService;

    public LayoutService(FloorRepository floorRepository, RoomRepository roomRepository, BedRepository bedRepository,
                         PropertyRepository propertyRepository, AccessGuard accessGuard, AuditService auditService) {
        this.floorRepository = floorRepository;
        this.roomRepository = roomRepository;
        this.bedRepository = bedRepository;
        this.propertyRepository = propertyRepository;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public FloorLayoutResponse get(Long propertyId, Long floorId) {
        accessGuard.requirePropertyAccess(propertyId);
        Floor floor = floorRepository.findWithBuildingById(floorId)
                .filter(f -> f.getBuilding().getPropertyId().equals(propertyId))
                .orElseThrow(() -> new NotFoundException("Floor", floorId));
        return toResponse(floor, roomRepository.findByFloorId(floorId));
    }

    @Transactional
    public FloorLayoutResponse update(Long propertyId, Long floorId, UpdateLayoutRequest request) {
        accessGuard.requirePropertyAccess(propertyId);
        Floor floor = lockFloor(floorId);
        if (!floor.getBuilding().getPropertyId().equals(propertyId)) {
            throw new NotFoundException("Floor", floorId);
        }
        Set<Long> seen = new HashSet<>();
        for (SpacePlacement placement : request.spaces()) {
            if (!seen.add(placement.id())) {
                throw new BadRequestException("DUPLICATE_SPACE", "Space " + placement.id() + " appears more than once");
            }
        }

        List<Room> rooms = roomRepository.findByFloorId(floorId);
        Map<Long, Room> byId = rooms.stream().collect(Collectors.toMap(Room::getId, Function.identity()));
        for (SpacePlacement placement : request.spaces()) {
            Room room = byId.get(placement.id());
            if (room == null) {
                throw new NotFoundException("Space on floor " + floorId, placement.id());
            }
            if (room.getVersion() != placement.version()) {
                throw new ConflictException("LAYOUT_STALE", LayoutGeometry.nameOf(room.getRoomNumber())
                        + " was changed by someone else (current version " + room.getVersion() + ", sent "
                        + placement.version() + "). Reload the layout and try again.");
            }
            // shape check only (all-or-nothing, non-negative); bounds and overlaps are checked on the whole floor below
            LayoutGeometry.toRect(room.getId(), LayoutGeometry.nameOf(room.getRoomNumber()),
                    placement.layoutX(), placement.layoutY(), placement.layoutW(), placement.layoutH());
            room.place(placement.layoutX(), placement.layoutY(), placement.layoutW(), placement.layoutH());
            room.decorate(placement.label() == null ? room.getLabel() : blankToNull(placement.label()),
                    placement.color() == null ? room.getColor() : blankToNull(placement.color()));
        }
        LayoutGeometry.validate(floor.getGridCols(), floor.getGridRows(), placed(rooms));

        // flush so a concurrent change to one of the rooms (bed added under its room lock) fails here as a 409 and the
        // response carries the new versions
        roomRepository.flush();
        auditService.record(orgOf(propertyId), "LAYOUT_UPDATED", "FLOOR", floorId,
                Map.of("spaces", request.spaces().stream().map(SpacePlacement::id).toList()));
        return toResponse(floor, rooms);
    }

    /** Label and/or grid size. Shrinking the grid is refused while a placed space would end up outside it. */
    @Transactional
    public FloorResponse updateFloor(Long floorId, UpdateFloorRequest request) {
        Floor floor = lockFloor(floorId);
        Long propertyId = floor.getBuilding().getPropertyId();
        accessGuard.requirePropertyAccess(propertyId);
        Map<String, Object> details = new HashMap<>();
        if (request.gridCols() != null || request.gridRows() != null) {
            int cols = request.gridCols() == null ? floor.getGridCols() : request.gridCols();
            int rows = request.gridRows() == null ? floor.getGridRows() : request.gridRows();
            LayoutGeometry.requireInsideGrid(cols, rows, placed(roomRepository.findByFloorId(floorId)));
            floor.resizeGrid(cols, rows);
            details.put("gridCols", cols);
            details.put("gridRows", rows);
        }
        if (request.label() != null) {
            floor.relabel(blankToNull(request.label()));
            details.put("label", request.label());
        }
        auditService.record(orgOf(propertyId), "FLOOR_UPDATED", "FLOOR", floorId, details);
        return FloorResponse.of(floor);
    }

    private Floor lockFloor(Long floorId) {
        return floorRepository.findByIdForUpdate(floorId).orElseThrow(() -> new NotFoundException("Floor", floorId));
    }

    private FloorLayoutResponse toResponse(Floor floor, List<Room> rooms) {
        // one query for the beds of every space on the floor (no N+1)
        Map<Long, List<Bed>> bedsByRoom = rooms.isEmpty() ? Map.of()
                : bedRepository.findByRoomIdIn(rooms.stream().map(Room::getId).toList()).stream()
                        .collect(Collectors.groupingBy(b -> b.getRoom().getId()));
        List<LayoutSpace> spaces = rooms.stream()
                .map(r -> LayoutSpace.of(r, bedsByRoom.getOrDefault(r.getId(), List.of())))
                .toList();
        Building building = floor.getBuilding();
        return new FloorLayoutResponse(floor.getId(), building.getPropertyId(), building.getId(), building.getCode(),
                floor.getFloorNumber(), floor.getLabel(), floor.getGridCols(), floor.getGridRows(), spaces);
    }

    private Long orgOf(Long propertyId) {
        return propertyRepository.findOrganizationIdById(propertyId).orElse(null);
    }

    private static List<Rect> placed(List<Room> rooms) {
        List<Rect> rects = new ArrayList<>();
        rooms.forEach(r -> LayoutGeometry.rectOf(r).ifPresent(rects::add));
        return rects;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
