package com.pgms.room;

import com.pgms.audit.AuditService;
import com.pgms.common.api.PageResponse;
import com.pgms.common.api.SortValidator;
import com.pgms.common.exception.BusinessRuleException;
import com.pgms.common.exception.ConflictException;
import com.pgms.common.exception.NotFoundException;
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

    /** Creates the room and one bed per unit of capacity (codes A, B, C ...). */
    @Transactional
    public RoomResponse create(Long propertyId, CreateRoomRequest request) {
        accessGuard.requirePropertyAccess(propertyId);
        Floor floor = floorRepository.findById(request.floorId())
                .filter(f -> f.getBuilding().getPropertyId().equals(propertyId))
                .orElseThrow(() -> new NotFoundException("Floor", request.floorId()));
        requireConsistentLayout(request.roomType(), request.capacity());
        Room room = roomRepository.save(new Room(propertyId, floor, request.roomNumber(), request.roomType(),
                request.capacity(), request.monthlyRent(), request.securityDeposit(), request.airConditioned(),
                request.attachedBathroom()));
        List<Bed> beds = new ArrayList<>();
        for (int i = 0; i < request.capacity(); i++) {
            beds.add(new Bed(room, bedCode(i)));
        }
        bedRepository.saveAll(beds);
        auditService.record(orgOf(propertyId), "ROOM_CREATED", "ROOM", room.getId(),
                Map.of("roomNumber", room.getRoomNumber(), "beds", request.capacity()));
        return toResponse(room, beds);
    }

    @Transactional(readOnly = true)
    public RoomResponse get(Long roomId) {
        Room room = loadAccessible(roomId);
        return toResponse(room, bedRepository.findByRoomId(roomId));
    }

    @Transactional(readOnly = true)
    public PageResponse<RoomResponse> search(Long propertyId, RoomType roomType, RoomStatus status, Boolean airConditioned,
                                             Pageable pageable) {
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
        Page<Room> rooms = roomRepository.findAll(spec, page);
        // one query for all beds of the page instead of one per room (N+1)
        Map<Long, List<Bed>> bedsByRoom = rooms.isEmpty() ? Map.of()
                : bedRepository.findByRoomIdIn(rooms.map(Room::getId).toList()).stream()
                        .collect(Collectors.groupingBy(b -> b.getRoom().getId()));
        return PageResponse.from(rooms, r -> toResponse(r, bedsByRoom.getOrDefault(r.getId(), List.of())));
    }

    /**
     * Updates pricing/amenities/layout. Rent changes apply to new allocations only; existing tenants keep the rent
     * snapshotted on their allocation. Capacity can shrink only by removing currently AVAILABLE beds.
     */
    @Transactional
    public RoomResponse update(Long roomId, UpdateRoomRequest request) {
        Room room = loadAccessible(roomId);
        requireConsistentLayout(request.roomType(), request.capacity());
        List<Bed> beds = new ArrayList<>(bedRepository.findByRoomId(roomId));
        int delta = request.capacity() - beds.size();
        if (delta > 0) {
            List<Bed> added = new ArrayList<>();
            int next = beds.stream().map(b -> b.getBedCode().charAt(0) - 'A').max(Integer::compare).orElse(-1) + 1;
            for (int i = 0; i < delta; i++) {
                added.add(new Bed(room, bedCode(next + i)));
            }
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
        room.changeLayout(request.roomType(), request.capacity());
        room.updatePricing(request.monthlyRent(), request.securityDeposit());
        room.updateAmenities(request.airConditioned(), request.attachedBathroom());
        auditService.record(orgOf(room.getPropertyId()), "ROOM_UPDATED", "ROOM", roomId,
                Map.of("monthlyRent", request.monthlyRent(), "capacity", request.capacity()));
        return toResponse(room, beds);
    }

    @Transactional
    public RoomResponse changeStatus(Long roomId, RoomStatus status) {
        Room room = loadAccessible(roomId);
        List<Bed> beds = bedRepository.findByRoomId(roomId);
        if (status == RoomStatus.INACTIVE && beds.stream().anyMatch(this::inUse)) {
            throw new ConflictException("ROOM_IN_USE", "Room has occupied or reserved beds");
        }
        room.changeStatus(status);
        auditService.record(orgOf(room.getPropertyId()), "ROOM_STATUS_CHANGED", "ROOM", roomId, Map.of("status", status.name()));
        return toResponse(room, beds);
    }

    @Transactional
    public void delete(Long roomId) {
        Room room = loadAccessible(roomId);
        List<Bed> beds = bedRepository.findByRoomId(roomId);
        if (beds.stream().anyMatch(this::inUse)) {
            throw new ConflictException("ROOM_IN_USE", "Room has occupied or reserved beds");
        }
        beds.forEach(b -> b.softDelete(clock.instant()));
        room.softDelete(clock.instant());
        auditService.record(orgOf(room.getPropertyId()), "ROOM_DELETED", "ROOM", roomId);
    }

    // ---- beds ----

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
            spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("room").get("monthlyRent"), maxRent));
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

    private boolean inUse(Bed bed) {
        return bed.getStatus() == BedStatus.OCCUPIED || bed.getStatus() == BedStatus.RESERVED;
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

    private static String bedCode(int index) {
        return String.valueOf((char) ('A' + index));
    }

    private static RoomResponse toResponse(Room room, List<Bed> beds) {
        Floor floor = room.getFloor();
        return new RoomResponse(room.getId(), room.getPropertyId(), floor.getId(), floor.getFloorNumber(),
                floor.getBuilding().getCode(), room.getRoomNumber(), room.getRoomType(), room.getCapacity(),
                room.getMonthlyRent(), room.getSecurityDeposit(), room.isAirConditioned(), room.isAttachedBathroom(),
                room.getStatus(), beds.stream().map(b -> new BedSummary(b.getId(), b.getBedCode(), b.getStatus())).toList());
    }
}
