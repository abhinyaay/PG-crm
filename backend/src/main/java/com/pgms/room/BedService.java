package com.pgms.room;

import com.pgms.audit.AuditService;
import com.pgms.common.exception.BadRequestException;
import com.pgms.common.exception.BusinessRuleException;
import com.pgms.common.exception.ConflictException;
import com.pgms.common.exception.NotFoundException;
import com.pgms.property.PropertyRepository;
import com.pgms.room.RoomDtos.AddBedRequest;
import com.pgms.room.RoomDtos.BedAttribute;
import com.pgms.room.RoomDtos.BedResponse;
import com.pgms.room.RoomDtos.UpdateBedRequest;
import com.pgms.security.AccessGuard;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Individual bed management for the layout editor: add a bed with a custom code, rename/relabel/reprice/reposition it,
 * remove it. The room's {@code capacity} must always equal its number of active beds, so every add/remove runs under
 * a row lock on the room (lock order room -> bed; booking flows lock beds only and never wait on a room, so the two
 * cannot deadlock). A ROOM's sharing type follows its bed count; halls/dormitories stay DORMITORY.
 */
@Service
public class BedService {

    private final RoomRepository roomRepository;
    private final BedRepository bedRepository;
    private final PropertyRepository propertyRepository;
    private final AccessGuard accessGuard;
    private final AuditService auditService;
    private final Clock clock;

    public BedService(RoomRepository roomRepository, BedRepository bedRepository, PropertyRepository propertyRepository,
                      AccessGuard accessGuard, AuditService auditService, Clock clock) {
        this.roomRepository = roomRepository;
        this.bedRepository = bedRepository;
        this.propertyRepository = propertyRepository;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public BedResponse addBed(Long roomId, AddBedRequest request) {
        Room room = lockRoom(roomId);
        SpaceKind kind = room.getSpaceKind();
        if (!kind.holdsBeds()) {
            throw new BusinessRuleException("SPACE_CANNOT_HOLD_BEDS", kind + " spaces cannot hold beds");
        }
        // read after the room lock was granted: sees every bed committed by an earlier concurrent add
        List<Bed> beds = bedRepository.findByRoomId(roomId);
        if (beds.size() >= kind.maxBeds()) {
            throw new BusinessRuleException("SPACE_FULL", "A " + kind + " space holds at most " + kind.maxBeds() + " beds");
        }
        List<String> taken = beds.stream().map(Bed::getBedCode).toList();
        String code = request.bedCode() == null ? BedCodes.next(taken, 1).get(0) : request.bedCode();
        if (taken.contains(code)) {
            throw new ConflictException("BED_EXISTS", "Bed code " + code + " already exists in room " + room.getRoomNumber());
        }
        requirePosition(request.posX(), request.posY());

        Bed bed = new Bed(room, code);
        bed.relabel(blankToNull(request.label()));
        bed.overridePrice(request.monthlyRentOverride(), request.securityDepositOverride());
        bed.position(request.posX(), request.posY());
        bedRepository.save(bed);
        room.syncCapacity(beds.size() + 1);
        roomRepository.flush();

        Map<String, Object> details = new HashMap<>();
        details.put("roomId", roomId);
        details.put("bedCode", code);
        details.put("capacity", room.getCapacity());
        auditService.record(orgOf(room.getPropertyId()), "BED_ADDED", "BED", bed.getId(), details);
        return BedResponse.of(bed);
    }

    @Transactional
    public BedResponse updateBed(Long bedId, UpdateBedRequest request) {
        requireNoSetAndClear(request);
        Long roomId = bedRepository.findRoomIdById(bedId).orElseThrow(() -> new NotFoundException("Bed", bedId));
        Room room = lockRoom(roomId);
        Bed bed = bedRepository.findByIdForUpdate(bedId).orElseThrow(() -> new NotFoundException("Bed", bedId));

        Map<String, Object> details = new HashMap<>();
        if (request.bedCode() != null && !request.bedCode().equals(bed.getBedCode())) {
            boolean taken = bedRepository.findByRoomId(roomId).stream()
                    .anyMatch(other -> !other.getId().equals(bedId) && other.getBedCode().equals(request.bedCode()));
            if (taken) {
                throw new ConflictException("BED_EXISTS",
                        "Bed code " + request.bedCode() + " already exists in room " + room.getRoomNumber());
            }
            details.put("bedCode", request.bedCode());
            details.put("previousBedCode", bed.getBedCode());
            bed.rename(request.bedCode());
        }
        if (request.clears(BedAttribute.LABEL)) {
            bed.relabel(null);
        } else if (request.label() != null) {
            bed.relabel(blankToNull(request.label()));
        }
        bed.overridePrice(
                pick(request.monthlyRentOverride(), request.clears(BedAttribute.MONTHLY_RENT_OVERRIDE), bed.getMonthlyRentOverride()),
                pick(request.securityDepositOverride(), request.clears(BedAttribute.SECURITY_DEPOSIT_OVERRIDE),
                        bed.getSecurityDepositOverride()));
        if (request.clears(BedAttribute.POSITION)) {
            bed.position(null, null);
        } else if (request.posX() != null || request.posY() != null) {
            requirePosition(request.posX(), request.posY());
            bed.position(request.posX(), request.posY());
        }
        bedRepository.flush();
        details.put("monthlyRentOverride", Objects.toString(bed.getMonthlyRentOverride(), "none"));
        details.put("securityDepositOverride", Objects.toString(bed.getSecurityDepositOverride(), "none"));
        auditService.record(orgOf(room.getPropertyId()), "BED_UPDATED", "BED", bedId, details);
        return BedResponse.of(bed);
    }

    /** Soft delete; a bed that is OCCUPIED or RESERVED cannot be removed (409), nor the last bed of a ROOM (422). */
    @Transactional
    public void removeBed(Long bedId) {
        Long roomId = bedRepository.findRoomIdById(bedId).orElseThrow(() -> new NotFoundException("Bed", bedId));
        Room room = lockRoom(roomId);
        // first load of the bed happens under its row lock, so the status checked below is the committed one
        Bed bed = bedRepository.findByIdForUpdate(bedId).orElseThrow(() -> new NotFoundException("Bed", bedId));
        int remaining = (int) bedRepository.countByRoomId(roomId) - 1;
        if (room.getSpaceKind() == SpaceKind.ROOM && remaining == 0 && !bed.isInUse()) {
            throw new BusinessRuleException("LAST_BED_IN_ROOM",
                    "A ROOM space needs at least one bed; delete the room or convert it instead");
        }
        bed.softDelete(clock.instant());
        room.syncCapacity(remaining);
        auditService.record(orgOf(room.getPropertyId()), "BED_REMOVED", "BED", bedId,
                Map.of("roomId", roomId, "bedCode", bed.getBedCode(), "capacity", remaining));
    }

    private Room lockRoom(Long roomId) {
        Room room = roomRepository.findByIdForUpdate(roomId).orElseThrow(() -> new NotFoundException("Room", roomId));
        accessGuard.requirePropertyAccess(room.getPropertyId());
        return room;
    }

    private Long orgOf(Long propertyId) {
        return propertyRepository.findOrganizationIdById(propertyId).orElse(null);
    }

    private static void requireNoSetAndClear(UpdateBedRequest request) {
        boolean conflict = request.clears(BedAttribute.LABEL) && request.label() != null
                || request.clears(BedAttribute.MONTHLY_RENT_OVERRIDE) && request.monthlyRentOverride() != null
                || request.clears(BedAttribute.SECURITY_DEPOSIT_OVERRIDE) && request.securityDepositOverride() != null
                || request.clears(BedAttribute.POSITION) && (request.posX() != null || request.posY() != null);
        if (conflict) {
            throw new BadRequestException("VALIDATION_FAILED", "An attribute cannot be set and cleared in the same request");
        }
    }

    private static void requirePosition(Integer posX, Integer posY) {
        if ((posX == null) != (posY == null)) {
            throw new BadRequestException("INVALID_POSITION", "posX and posY must be given together");
        }
    }

    private static <T> T pick(T requested, boolean clear, T current) {
        if (clear) {
            return null;
        }
        return requested != null ? requested : current;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
