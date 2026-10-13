package com.pgms.room;

import com.pgms.audit.AuditService;
import com.pgms.common.api.PageResponse;
import com.pgms.common.api.SortValidator;
import com.pgms.common.exception.BadRequestException;
import com.pgms.common.exception.BusinessRuleException;
import com.pgms.common.exception.ConflictException;
import com.pgms.common.exception.NotFoundException;
import com.pgms.layout.LayoutGeometry;
import com.pgms.layout.LayoutGeometry.Rect;
import com.pgms.property.Floor;
import com.pgms.property.FloorRepository;
import com.pgms.property.PropertyRepository;
import com.pgms.room.RoomDtos.BedResponse;
import com.pgms.room.RoomDtos.BedSummary;
import com.pgms.room.RoomDtos.CreateRoomRequest;
import com.pgms.room.RoomDtos.RoomResponse;
import com.pgms.room.RoomDtos.UpdateRoomRequest;
import com.pgms.security.AccessGuard;
import com.pgms.security.AccessScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomService {

    private static final Set<String> ROOM_SORTABLE = Set.of("roomNumber", "monthlyRent", "capacity", "createdAt");
    private static final Set<String> BED_SORTABLE = Set.of("bedCode", "status", "createdAt");

    private final RoomRepository roomRepository;
    private final BedRepository bedRepository;
    private final FloorRepository floorRepository;
    private final PropertyRepository propertyRepository;
    private final AccessGuard accessGuard;
    private final AuditService auditService;
    private final Clock clock;

    public RoomService(RoomRepository roomRepository, BedRepository bedRepository, FloorRepository floorRepository,
                       PropertyRepository propertyRepository, AccessGuard accessGuard, AuditService auditService,
                       Clock clock) {
        this.roomRepository = roomRepository;
        this.bedRepository = bedRepository;
        this.floorRepository = floorRepository;
        this.propertyRepository = propertyRepository;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * Creates a space and one bed per unit of capacity (codes A, B, C ...). A space created with geometry is validated
     * against the other spaces of the floor under the floor row lock, exactly like a layout update.
     */
    @Transactional
    public RoomResponse create(Long propertyId, CreateRoomRequest request) {
        accessGuard.requirePropertyAccess(propertyId);
        SpaceKind kind = request.kind();
        Optional<Rect> placement = LayoutGeometry.toRect(null, LayoutGeometry.nameOf(request.roomNumber()),
                request.layoutX(), request.layoutY(), request.layoutW(), request.layoutH());
        Floor floor = (placement.isPresent() ? floorRepository.findByIdForUpdate(request.floorId())
                : floorRepository.findById(request.floorId()))
                .filter(f -> f.getBuilding().getPropertyId().equals(propertyId))
                .orElseThrow(() -> new NotFoundException("Floor", request.floorId()));

        RoomType roomType = request.roomType();
        int capacity = request.capacity() == null ? 0 : request.capacity();
        if (kind == SpaceKind.ROOM) {
            requireConsistentLayout(roomType, capacity);
        } else if (kind.isOpenPlan()) {
            roomType = requireOpenPlanType(roomType);
        } else {
            requireNoRoomType(kind, roomType);
        }
        if (placement.isPresent()) {
            List<Rect> rects = new ArrayList<>();
            roomRepository.findByFloorId(floor.getId()).forEach(r -> LayoutGeometry.rectOf(r).ifPresent(rects::add));
            rects.add(placement.get());
            LayoutGeometry.validate(floor.getGridCols(), floor.getGridRows(), rects);
        }

        Room room = new Room(propertyId, floor, request.roomNumber(), kind, roomType, capacity,
                orZero(request.monthlyRent()), orZero(request.securityDeposit()), request.airConditioned(),
                request.attachedBathroom());
        placement.ifPresent(p -> room.place(p.x(), p.y(), p.w(), p.h()));
        room.decorate(blankToNull(request.label()), request.color());
        roomRepository.save(room);
        List<Bed> beds = BedCodes.next(List.of(), capacity).stream().map(code -> new Bed(room, code)).toList();
        bedRepository.saveAll(beds);
        auditService.record(orgOf(propertyId), "ROOM_CREATED", "ROOM", room.getId(),
                Map.of("roomNumber", room.getRoomNumber(), "spaceKind", kind.name(), "beds", capacity));
        return toResponse(room, beds);
    }

    @Transactional(readOnly = true)
    public RoomResponse get(Long roomId) {
        Room room = loadAccessible(roomId);
        return toResponse(room, bedRepository.findByRoomId(roomId));
    }

    @Transactional(readOnly = true)
    public PageResponse<RoomResponse> search(Long propertyId, RoomType roomType, RoomStatus status, Boolean airConditioned,
                                             SpaceKind spaceKind, Pageable pageable) {
        AccessScope scope = accessGuard.scopeFor(propertyId);
        Pageable page = SortValidator.validate(pageable, ROOM_SORTABLE, Sort.by("roomNumber"));
        if (scope.isEmpty()) {
            return PageResponse.from(Page.<Room>empty(page), r -> toResponse(r, List.of()));
        }
        Specification<Room> spec = scope.toPropertySpecification();
        if (roomType != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("roomType"), roomType));
        }
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        if (airConditioned != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("airConditioned"), airConditioned));
        }
        if (spaceKind != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("spaceKind"), spaceKind));
        }
        Page<Room> rooms = roomRepository.findAll(spec, page);
        // one query for all beds of the page instead of one per room (N+1)
        Map<Long, List<Bed>> bedsByRoom = rooms.isEmpty() ? Map.of()
                : bedRepository.findByRoomIdIn(rooms.map(Room::getId).toList()).stream()
                        .collect(Collectors.groupingBy(b -> b.getRoom().getId()));
        return PageResponse.from(rooms, r -> toResponse(r, bedsByRoom.getOrDefault(r.getId(), List.of())));
    }

    /**
     * Updates pricing/amenities/capacity. Rent changes apply to new allocations only; existing tenants keep the rent
     * snapshotted on their allocation. Capacity can shrink only by removing currently free beds. The room row is
     * locked so this cannot interleave with individual bed adds/removals (see {@link BedService}).
     */
    @Transactional
    public RoomResponse update(Long roomId, UpdateRoomRequest request) {
        Room room = roomRepository.findByIdForUpdate(roomId).orElseThrow(() -> new NotFoundException("Room", roomId));
        accessGuard.requirePropertyAccess(room.getPropertyId());
        SpaceKind kind = room.getSpaceKind();
        RoomType roomType;
        int capacity;
        if (kind == SpaceKind.ROOM) {
            if (request.roomType() == null || request.capacity() == null) {
                throw new BadRequestException("VALIDATION_FAILED", "roomType and capacity are required for ROOM spaces");
            }
            roomType = request.roomType();
            capacity = request.capacity();
            requireConsistentLayout(roomType, capacity);
        } else if (kind.isOpenPlan()) {
            roomType = requireOpenPlanType(request.roomType());
            capacity = request.capacity() == null ? room.getCapacity() : request.capacity();
        } else {
            requireNoRoomType(kind, request.roomType());
            if (request.capacity() != null && request.capacity() != 0) {
                throw new BusinessRuleException("SPACE_CANNOT_HOLD_BEDS", kind + " spaces cannot hold beds");
            }
            roomType = null;
            capacity = 0;
        }
        List<Bed> beds = new ArrayList<>(bedRepository.findByRoomId(roomId));
        int delta = capacity - beds.size();
        if (delta > 0) {
            List<Bed> added = BedCodes.next(beds.stream().map(Bed::getBedCode).toList(), delta).stream()
                    .map(code -> new Bed(room, code)).toList();
            bedRepository.saveAll(added);
            beds.addAll(added);
        } else if (delta < 0) {
            List<Bed> removable = beds.stream()
                    .filter(b -> b.getStatus() == BedStatus.AVAILABLE || b.getStatus() == BedStatus.MAINTENANCE)
                    .sorted(Comparator.comparing(Bed::getBedCode).reversed())
                    .limit(-delta)
                    .toList();
            if (removable.size() < -delta) {
                throw new ConflictException("BEDS_IN_USE", "Cannot reduce capacity: not enough free beds to remove");
            }
            removable.forEach(b -> b.softDelete(clock.instant()));
            beds.removeAll(removable);
        }
        room.changeLayout(roomType, capacity);
        room.updatePricing(request.monthlyRent(), request.securityDeposit());
        room.updateAmenities(request.airConditioned(), request.attachedBathroom());
        auditService.record(orgOf(room.getPropertyId()), "ROOM_UPDATED", "ROOM", roomId,
                Map.of("monthlyRent", request.monthlyRent(), "capacity", capacity));
        beds.sort(Comparator.comparing(Bed::getBedCode));
        roomRepository.flush(); // the response carries the new version for optimistic layout edits
        return toResponse(room, beds);
    }

    @Transactional
    public RoomResponse changeStatus(Long roomId, RoomStatus status) {
        Room room = loadAccessible(roomId);
        List<Bed> beds = bedRepository.findByRoomId(roomId);
        if (status == RoomStatus.INACTIVE && beds.stream().anyMatch(Bed::isInUse)) {
            throw new ConflictException("ROOM_IN_USE", "Room has occupied or reserved beds");
        }
        room.changeStatus(status);
        auditService.record(orgOf(room.getPropertyId()), "ROOM_STATUS_CHANGED", "ROOM", roomId, Map.of("status", status.name()));
        roomRepository.flush();
        return toResponse(room, beds);
    }

    @Transactional
    public void delete(Long roomId) {
        Room room = loadAccessible(roomId);
        List<Bed> beds = bedRepository.findByRoomId(roomId);
        if (beds.stream().anyMatch(Bed::isInUse)) {
            throw new ConflictException("ROOM_IN_USE", "Room has occupied or reserved beds");
        }
        beds.forEach(b -> b.softDelete(clock.instant()));
        room.softDelete(clock.instant());
        auditService.record(orgOf(room.getPropertyId()), "ROOM_DELETED", "ROOM", roomId);
    }

    // ---- beds ----

    /** {@code maxRent} filters on the price a new occupant would pay: the bed's override, else the room's rent. */
    @Transactional(readOnly = true)
    public PageResponse<BedResponse> searchBeds(Long propertyId, Long roomId, BedStatus status, RoomType roomType,
                                                Boolean airConditioned, BigDecimal maxRent, Pageable pageable) {
        AccessScope scope = accessGuard.scopeFor(propertyId);
        Pageable page = SortValidator.validate(pageable, BED_SORTABLE, Sort.by("bedCode"));
        if (scope.isEmpty()) {
            return PageResponse.from(Page.<Bed>empty(page), BedResponse::of);
        }
        Specification<Bed> spec = scope.toPropertySpecification();
        if (roomId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("room").get("id"), roomId));
        }
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        if (roomType != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("room").get("roomType"), roomType));
        }
        if (airConditioned != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("room").get("airConditioned"), airConditioned));
        }
        if (maxRent != null) {
            spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(
                    cb.coalesce(root.<BigDecimal>get("monthlyRentOverride"), root.get("room").<BigDecimal>get("monthlyRent")),
                    maxRent));
        }
        // only beds of active rooms are offered
        spec = spec.and((root, q, cb) -> cb.equal(root.get("room").get("status"), RoomStatus.ACTIVE));
        return PageResponse.from(bedRepository.findAll(spec, page), BedResponse::of);
    }

    @Transactional(readOnly = true)
    public BedResponse getBed(Long bedId) {
        Bed bed = bedRepository.findById(bedId).orElseThrow(() -> new NotFoundException("Bed", bedId));
        accessGuard.requirePropertyAccess(bed.getPropertyId());
        return BedResponse.of(bed);
    }

    /** Manual status changes are limited to maintenance toggling; occupancy states belong to booking workflows. */
    @Transactional
    public BedResponse changeBedStatus(Long bedId, BedStatus target) {
        Bed bed = bedRepository.findByIdForUpdate(bedId).orElseThrow(() -> new NotFoundException("Bed", bedId));
        accessGuard.requirePropertyAccess(bed.getPropertyId());
        switch (target) {
            case MAINTENANCE -> bed.startMaintenance();
            case AVAILABLE -> bed.endMaintenance();
            default -> throw new BusinessRuleException("INVALID_BED_TRANSITION",
                    "Beds become RESERVED/OCCUPIED only through bookings and check-ins");
        }
        auditService.record(orgOf(bed.getPropertyId()), "BED_STATUS_CHANGED", "BED", bedId, Map.of("status", target.name()));
        return BedResponse.of(bed);
    }

    private Room loadAccessible(Long roomId) {
        Room room = roomRepository.findById(roomId).orElseThrow(() -> new NotFoundException("Room", roomId));
        accessGuard.requirePropertyAccess(room.getPropertyId());
        return room;
    }

    private Long orgOf(Long propertyId) {
        return propertyRepository.findOrganizationIdById(propertyId).orElse(null);
    }

    private static void requireConsistentLayout(RoomType type, int capacity) {
        if (!type.allowsCapacity(capacity)) {
            throw new BusinessRuleException("INVALID_CAPACITY", "Capacity " + capacity + " is not valid for a " + type + " room");
        }
    }

    /** Halls and dormitories are always DORMITORY-type (that is how bed search and analytics group them). */
    private static RoomType requireOpenPlanType(RoomType requested) {
        if (requested != null && requested != RoomType.DORMITORY) {
            throw new BusinessRuleException("INVALID_ROOM_TYPE", "Halls and dormitories always have room type DORMITORY");
        }
        return RoomType.DORMITORY;
    }

    private static void requireNoRoomType(SpaceKind kind, RoomType requested) {
        if (requested != null) {
            throw new BusinessRuleException("INVALID_ROOM_TYPE", kind + " spaces hold no beds and have no room type");
        }
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static RoomResponse toResponse(Room room, List<Bed> beds) {
        Floor floor = room.getFloor();
        return new RoomResponse(room.getId(), room.getPropertyId(), floor.getId(), floor.getFloorNumber(),
                floor.getBuilding().getCode(), room.getRoomNumber(), room.getRoomType(), room.getCapacity(),
                room.getMonthlyRent(), room.getSecurityDeposit(), room.isAirConditioned(), room.isAttachedBathroom(),
                room.getStatus(), beds.stream().map(BedSummary::of).toList(), room.getSpaceKind(), room.getLayoutX(),
                room.getLayoutY(), room.getLayoutW(), room.getLayoutH(), room.getLabel(), room.getColor(), room.getVersion());
    }
}
