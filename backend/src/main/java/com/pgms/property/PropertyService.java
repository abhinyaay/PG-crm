package com.pgms.property;

import com.pgms.audit.AuditService;
import com.pgms.common.api.PageResponse;
import com.pgms.common.api.SortValidator;
import com.pgms.common.exception.BusinessRuleException;
import com.pgms.common.exception.ConflictException;
import com.pgms.common.exception.NotFoundException;
import com.pgms.property.PropertyDtos.AssignmentResponse;
import com.pgms.property.PropertyDtos.BuildingResponse;
import com.pgms.property.PropertyDtos.CreateBuildingRequest;
import com.pgms.property.PropertyDtos.CreateFloorRequest;
import com.pgms.property.PropertyDtos.CreatePropertyRequest;
import com.pgms.property.PropertyDtos.FloorResponse;
import com.pgms.property.PropertyDtos.OccupancyResponse;
import com.pgms.property.PropertyDtos.PropertyResponse;
import com.pgms.property.PropertyDtos.UpdatePropertyRequest;
import com.pgms.room.BedRepository;
import com.pgms.room.BedStatus;
import com.pgms.security.AccessGuard;
import com.pgms.security.AccessScope;
import com.pgms.user.User;
import com.pgms.user.UserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PropertyService {

    private static final Set<String> SORTABLE = Set.of("name", "code", "createdAt");

    private final PropertyRepository propertyRepository;
    private final BuildingRepository buildingRepository;
    private final FloorRepository floorRepository;
    private final PropertyAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;
    private final BedRepository bedRepository;
    private final PropertyCache propertyCache;
    private final AccessGuard accessGuard;
    private final AuditService auditService;
    private final Clock clock;

    public PropertyService(PropertyRepository propertyRepository, BuildingRepository buildingRepository,
                           FloorRepository floorRepository, PropertyAssignmentRepository assignmentRepository,
                           UserRepository userRepository, BedRepository bedRepository, PropertyCache propertyCache,
                           AccessGuard accessGuard, AuditService auditService, Clock clock) {
        this.propertyRepository = propertyRepository;
        this.buildingRepository = buildingRepository;
        this.floorRepository = floorRepository;
        this.assignmentRepository = assignmentRepository;
        this.userRepository = userRepository;
        this.bedRepository = bedRepository;
        this.propertyCache = propertyCache;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public PropertyResponse create(CreatePropertyRequest request) {
        Long organizationId = accessGuard.resolveOrganizationForCreate(request.organizationId());
        Property property = propertyRepository.save(new Property(organizationId, request.code(), request.name(),
                request.description(), request.type(), request.address().toEntity(), request.contactPhone(),
                request.rentDueDay() == null ? 5 : request.rentDueDay()));
        auditService.record(organizationId, "PROPERTY_CREATED", "PROPERTY", property.getId(), Map.of("code", request.code()));
        return PropertyResponse.of(property);
    }

    public PropertyResponse get(Long id) {
        accessGuard.requirePropertyAccess(id);
        return propertyCache.get(id);
    }

    @Transactional(readOnly = true)
    public PageResponse<PropertyResponse> search(PropertyStatus status, String city, String q, Pageable pageable) {
        AccessScope scope = accessGuard.scopeFor(null);
        Pageable page = SortValidator.validate(pageable, SORTABLE, Sort.by("name"));
        if (scope.isEmpty()) {
            return PageResponse.from(Page.empty(page), PropertyResponse::of);
        }
        Specification<Property> spec = scope.toSpecification("organizationId", "id");
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (city != null && !city.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(cb.lower(root.get("address").get("city")), city.trim().toLowerCase()));
        }
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.or(cb.like(cb.lower(root.get("name")), like),
                    cb.like(cb.lower(root.get("code")), like)));
        }
        return PageResponse.from(propertyRepository.findAll(spec, page), PropertyResponse::of);
    }

    @Transactional
    public PropertyResponse update(Long id, UpdatePropertyRequest request) {
        Property property = loadAccessible(id);
        property.update(request.name(), request.description(), request.type(), request.address().toEntity(),
                request.contactPhone(), request.rentDueDay());
        auditService.record(property.getOrganizationId(), "PROPERTY_UPDATED", "PROPERTY", id);
        propertyCache.evict(id);
        return PropertyResponse.of(property);
    }

    @Transactional
    public PropertyResponse changeStatus(Long id, PropertyStatus status) {
        Property property = loadAccessible(id);
        property.changeStatus(status);
        auditService.record(property.getOrganizationId(), "PROPERTY_STATUS_CHANGED", "PROPERTY", id,
                Map.of("status", status.name()));
        propertyCache.evict(id);
        return PropertyResponse.of(property);
    }

    @Transactional
    public void delete(Long id) {
        Property property = loadAccessible(id);
        Map<BedStatus, Long> counts = bedCounts(List.of(id));
        if (counts.get(BedStatus.OCCUPIED) > 0 || counts.get(BedStatus.RESERVED) > 0) {
            throw new ConflictException("PROPERTY_IN_USE", "Property has occupied or reserved beds and cannot be deleted");
        }
        property.softDelete(clock.instant());
        auditService.record(property.getOrganizationId(), "PROPERTY_DELETED", "PROPERTY", id);
        propertyCache.evict(id);
    }

    /** Live occupancy straight from bed rows (not cached - this is used to make allocation decisions). */
    @Transactional(readOnly = true)
    public OccupancyResponse occupancy(Long id) {
        accessGuard.requirePropertyAccess(id);
        Map<BedStatus, Long> counts = bedCounts(List.of(id));
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        long occupied = counts.get(BedStatus.OCCUPIED);
        BigDecimal rate = total == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(occupied * 100).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        return new OccupancyResponse(id, total, occupied, counts.get(BedStatus.RESERVED), counts.get(BedStatus.AVAILABLE),
                counts.get(BedStatus.MAINTENANCE), rate);
    }

    // ---- structure: buildings & floors ----

    @Transactional
    public BuildingResponse addBuilding(Long propertyId, CreateBuildingRequest request) {
        Property property = loadAccessible(propertyId);
        Building building = buildingRepository.save(new Building(propertyId, request.code(), request.name()));
        auditService.record(property.getOrganizationId(), "BUILDING_CREATED", "BUILDING", building.getId());
        return BuildingResponse.of(building);
    }

    @Transactional(readOnly = true)
    public List<BuildingResponse> buildings(Long propertyId) {
        accessGuard.requirePropertyAccess(propertyId);
        return buildingRepository.findByPropertyIdOrderByCode(propertyId).stream().map(BuildingResponse::of).toList();
    }

    @Transactional
    public FloorResponse addFloor(Long propertyId, Long buildingId, CreateFloorRequest request) {
        Property property = loadAccessible(propertyId);
        Building building = loadBuilding(propertyId, buildingId);
        Floor floor = floorRepository.save(new Floor(building, request.floorNumber(), request.label()));
        auditService.record(property.getOrganizationId(), "FLOOR_CREATED", "FLOOR", floor.getId());
        return FloorResponse.of(floor);
    }

    @Transactional(readOnly = true)
    public List<FloorResponse> floors(Long propertyId, Long buildingId) {
        accessGuard.requirePropertyAccess(propertyId);
        loadBuilding(propertyId, buildingId);
        return floorRepository.findByBuildingIdOrderByFloorNumber(buildingId).stream().map(FloorResponse::of).toList();
    }

    // ---- staff assignments ----

    @Transactional
    public AssignmentResponse assign(Long propertyId, Long userId) {
        Property property = loadAccessible(propertyId);
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));
        if (!Objects.equals(user.getOrganizationId(), property.getOrganizationId())) {
            throw new NotFoundException("User", userId);
        }
        if (!user.getRoleName().isPropertyScoped()) {
            throw new BusinessRuleException("ASSIGNMENT_NOT_APPLICABLE", "Only MANAGER and STAFF users are assigned to properties");
        }
        if (assignmentRepository.existsByPropertyIdAndUserId(propertyId, userId)) {
            throw new ConflictException("ALREADY_ASSIGNED", "User is already assigned to this property");
        }
        PropertyAssignment assignment = assignmentRepository.save(
                new PropertyAssignment(propertyId, userId, clock.instant(), accessGuard.currentUser().id()));
        auditService.record(property.getOrganizationId(), "USER_ASSIGNED", "PROPERTY", propertyId, Map.of("userId", userId));
        return new AssignmentResponse(userId, user.getFullName(), user.getEmail(), user.getRoleName(), assignment.getAssignedAt());
    }

    @Transactional
    public void unassign(Long propertyId, Long userId) {
        Property property = loadAccessible(propertyId);
        PropertyAssignment assignment = assignmentRepository.findByPropertyIdAndUserId(propertyId, userId)
                .orElseThrow(() -> new NotFoundException("Assignment", propertyId + "/" + userId));
        assignmentRepository.delete(assignment);
        auditService.record(property.getOrganizationId(), "USER_UNASSIGNED", "PROPERTY", propertyId, Map.of("userId", userId));
    }

    @Transactional(readOnly = true)
    public List<AssignmentResponse> assignments(Long propertyId) {
        accessGuard.requirePropertyAccess(propertyId);
        List<PropertyAssignment> assignments = assignmentRepository.findByPropertyId(propertyId);
        Map<Long, User> users = userRepository.findAllById(assignments.stream().map(PropertyAssignment::getUserId).toList())
                .stream().collect(Collectors.toMap(User::getId, u -> u));
        return assignments.stream()
                .filter(a -> users.containsKey(a.getUserId()))
                .map(a -> {
                    User u = users.get(a.getUserId());
                    return new AssignmentResponse(u.getId(), u.getFullName(), u.getEmail(), u.getRoleName(), a.getAssignedAt());
                })
                .toList();
    }

    private Property loadAccessible(Long id) {
        accessGuard.requirePropertyAccess(id);
        return propertyRepository.findById(id).orElseThrow(() -> new NotFoundException("Property", id));
    }

    private Building loadBuilding(Long propertyId, Long buildingId) {
        return buildingRepository.findById(buildingId)
                .filter(b -> b.getPropertyId().equals(propertyId))
                .orElseThrow(() -> new NotFoundException("Building", buildingId));
    }

    private Map<BedStatus, Long> bedCounts(List<Long> propertyIds) {
        Map<BedStatus, Long> counts = new EnumMap<>(BedStatus.class);
        for (BedStatus status : BedStatus.values()) {
            counts.put(status, 0L);
        }
        bedRepository.countByStatusForProperties(propertyIds).forEach(c -> counts.put(c.getStatus(), c.getTotal()));
        return counts;
    }
}
