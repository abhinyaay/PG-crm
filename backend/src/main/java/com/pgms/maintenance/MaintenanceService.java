package com.pgms.maintenance;

import com.pgms.audit.AuditService;
import com.pgms.booking.Allocation;
import com.pgms.booking.AllocationRepository;
import com.pgms.booking.AllocationStatus;
import com.pgms.common.api.PageResponse;
import com.pgms.common.api.SortValidator;
import com.pgms.common.domain.Priority;
import com.pgms.common.exception.BadRequestException;
import com.pgms.common.exception.BusinessRuleException;
import com.pgms.common.exception.ForbiddenException;
import com.pgms.common.exception.NotFoundException;
import com.pgms.common.util.ReferenceNumberGenerator;
import com.pgms.maintenance.MaintenanceDtos.CreateMaintenanceRequest;
import com.pgms.maintenance.MaintenanceDtos.HistoryEntry;
import com.pgms.maintenance.MaintenanceDtos.MaintenanceResponse;
import com.pgms.property.PropertyRepository;
import com.pgms.room.BedRepository;
import com.pgms.room.RoomRepository;
import com.pgms.security.AccessGuard;
import com.pgms.security.AccessScope;
import com.pgms.security.CurrentUser;
import com.pgms.tenant.Tenant;
import com.pgms.user.RoleName;
import com.pgms.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaintenanceService {

    private static final Set<String> SORTABLE = Set.of("createdAt", "slaDueAt", "priority", "status");
    private static final Set<MaintenanceStatus> TENANT_ALLOWED = EnumSet.of(MaintenanceStatus.CLOSED,
            MaintenanceStatus.CANCELLED, MaintenanceStatus.IN_PROGRESS);
    private static final Set<MaintenanceStatus> STAFF_ALLOWED = EnumSet.of(MaintenanceStatus.IN_PROGRESS,
            MaintenanceStatus.RESOLVED);

    private final MaintenanceRepository repository;
    private final MaintenanceStatusHistoryRepository historyRepository;
    private final AllocationRepository allocationRepository;
    private final BedRepository bedRepository;
    private final RoomRepository roomRepository;
    private final PropertyRepository propertyRepository;
    private final UserRepository userRepository;
    private final ReferenceNumberGenerator referenceNumbers;
    private final AccessGuard accessGuard;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public MaintenanceService(MaintenanceRepository repository, MaintenanceStatusHistoryRepository historyRepository,
                              AllocationRepository allocationRepository, BedRepository bedRepository,
                              RoomRepository roomRepository, PropertyRepository propertyRepository,
                              UserRepository userRepository, ReferenceNumberGenerator referenceNumbers, AccessGuard accessGuard,
                              AuditService auditService, ApplicationEventPublisher events, Clock clock) {
        this.repository = repository;
        this.historyRepository = historyRepository;
        this.allocationRepository = allocationRepository;
        this.bedRepository = bedRepository;
        this.roomRepository = roomRepository;
        this.propertyRepository = propertyRepository;
        this.userRepository = userRepository;
        this.referenceNumbers = referenceNumbers;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public MaintenanceResponse create(CreateMaintenanceRequest request) {
        CurrentUser user = accessGuard.currentUser();
        Long propertyId;
        Long roomId;
        Long tenantId = null;
        if (user.is(RoleName.TENANT)) {
            Tenant tenant = accessGuard.requireCurrentTenant();
            Allocation allocation = allocationRepository.findByTenantIdAndStatus(tenant.getId(), AllocationStatus.ACTIVE)
                    .orElseThrow(() -> new BusinessRuleException("NOT_A_RESIDENT", "Only checked-in tenants can raise requests"));
            propertyId = allocation.getPropertyId();
            roomId = bedRepository.findById(allocation.getBedId()).map(b -> b.getRoom().getId()).orElse(null);
            tenantId = tenant.getId();
        } else {
            if (request.propertyId() == null) {
                throw new BadRequestException("PROPERTY_REQUIRED", "propertyId is required");
            }
            propertyId = request.propertyId();
            accessGuard.requirePropertyAccess(propertyId);
            roomId = request.roomId();
            if (roomId != null) {
                roomRepository.findById(roomId).filter(r -> r.getPropertyId().equals(propertyId))
                        .orElseThrow(() -> new NotFoundException("Room", request.roomId()));
            }
        }
        Long organizationId = propertyRepository.findOrganizationIdById(propertyId)
                .orElseThrow(() -> new NotFoundException("Property", propertyId));
        Instant now = clock.instant();
        MaintenanceRequest ticket = repository.save(new MaintenanceRequest(
                referenceNumbers.next(ReferenceNumberGenerator.Kind.MAINTENANCE), organizationId, propertyId, roomId, tenantId,
                user.id(), request.category(), request.priority(), request.title(), request.description(), now));
        historyRepository.save(new MaintenanceStatusHistory(ticket.getId(), null, MaintenanceStatus.OPEN, user.id(),
                "Request created", now));
        auditService.record(organizationId, "MAINTENANCE_CREATED", "MAINTENANCE", ticket.getId(),
                Map.of("priority", request.priority().name(), "category", request.category().name()));
        publishStatus(ticket, null);
        return MaintenanceResponse.of(ticket);
    }

    @Transactional
    public MaintenanceResponse assign(Long id, Long assigneeUserId) {
        MaintenanceRequest ticket = lockForStaff(id);
        boolean eligible = userRepository.findActiveAssignedToProperty(ticket.getPropertyId(),
                        Set.of(RoleName.STAFF, RoleName.MANAGER)).stream()
                .anyMatch(u -> u.getId().equals(assigneeUserId));
        if (!eligible) {
            throw new BusinessRuleException("INVALID_ASSIGNEE", "Assignee must be an active STAFF/MANAGER assigned to this property");
        }
        Instant now = clock.instant();
        MaintenanceStatus previous = ticket.assignTo(assigneeUserId, now);
        historyRepository.save(new MaintenanceStatusHistory(id, previous, MaintenanceStatus.ASSIGNED,
                accessGuard.currentUser().id(), "Assigned to user " + assigneeUserId, now));
        auditService.record(ticket.getOrganizationId(), "MAINTENANCE_ASSIGNED", "MAINTENANCE", id,
                Map.of("assigneeUserId", assigneeUserId));
        publishStatus(ticket, previous);
        return MaintenanceResponse.of(ticket);
    }

    /**
     * Status changes with role rules on top of the state machine: tenants may close/cancel/reopen their own tickets,
     * staff may only progress tickets assigned to them, managers/admins may do any valid transition.
     */
    @Transactional
    public MaintenanceResponse changeStatus(Long id, MaintenanceStatus target, String notes) {
        CurrentUser user = accessGuard.currentUser();
        MaintenanceRequest ticket = repository.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("MaintenanceRequest", id));
        requireReadAccess(ticket, user);
        if (target == MaintenanceStatus.ASSIGNED) {
            throw new BadRequestException("USE_ASSIGN_ENDPOINT", "Use POST /maintenance/{id}/assign to assign");
        }
        if (user.is(RoleName.TENANT)) {
            // CANCELLED only while still OPEN; IN_PROGRESS only as "reopen" of a RESOLVED ticket, never "work started"
            boolean allowed = TENANT_ALLOWED.contains(target)
                    && (target != MaintenanceStatus.CANCELLED || ticket.getStatus() == MaintenanceStatus.OPEN)
                    && (target != MaintenanceStatus.IN_PROGRESS || ticket.getStatus() == MaintenanceStatus.RESOLVED);
            if (!allowed) {
                throw new ForbiddenException("Tenants can only close, cancel (while open) or reopen their requests");
            }
        } else if (user.is(RoleName.STAFF)) {
            if (!STAFF_ALLOWED.contains(target) || !user.id().equals(ticket.getAssignedToUserId())) {
                throw new ForbiddenException("Staff can only start or resolve tickets assigned to them");
            }
        }
        Instant now = clock.instant();
        MaintenanceStatus previous = ticket.transitionTo(target, notes, now);
        historyRepository.save(new MaintenanceStatusHistory(id, previous, target, user.id(), notes, now));
        auditService.record(ticket.getOrganizationId(), "MAINTENANCE_STATUS_CHANGED", "MAINTENANCE", id,
                Map.of("from", previous.name(), "to", target.name()));
        publishStatus(ticket, previous);
        return MaintenanceResponse.of(ticket);
    }

    @Transactional(readOnly = true)
    public MaintenanceResponse get(Long id) {
        MaintenanceRequest ticket = repository.findById(id).orElseThrow(() -> new NotFoundException("MaintenanceRequest", id));
        requireReadAccess(ticket, accessGuard.currentUser());
        return MaintenanceResponse.of(ticket);
    }

    @Transactional(readOnly = true)
    public List<HistoryEntry> history(Long id) {
        get(id);
        return historyRepository.findByRequestIdOrderByChangedAtAscIdAsc(id).stream().map(HistoryEntry::of).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<MaintenanceResponse> search(Long propertyId, MaintenanceStatus status, Priority priority,
                                                    MaintenanceCategory category, Long assigneeUserId, Boolean slaBreached,
                                                    boolean assignedToMe, Pageable pageable) {
        CurrentUser user = accessGuard.currentUser();
        Pageable page = SortValidator.validate(pageable, SORTABLE, Sort.by(Sort.Direction.DESC, "createdAt"));
        Specification<MaintenanceRequest> spec;
        if (user.is(RoleName.TENANT)) {
            Long ownId = accessGuard.requireCurrentTenant().getId();
            spec = (root, q, cb) -> cb.equal(root.get("tenantId"), ownId);
        } else {
            AccessScope scope = accessGuard.scopeFor(propertyId);
            if (scope.isEmpty()) {
                return PageResponse.from(Page.<MaintenanceRequest>empty(page), MaintenanceResponse::of);
            }
            spec = scope.toSpecification();
        }
        Long assignee = assignedToMe ? user.id() : assigneeUserId;
        if (assignee != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("assignedToUserId"), assignee));
        }
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        if (priority != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("priority"), priority));
        }
        if (category != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("category"), category));
        }
        if (slaBreached != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("slaBreached"), slaBreached));
        }
        return PageResponse.from(repository.findAll(spec, page), MaintenanceResponse::of);
    }

    /** Flags a ticket whose SLA lapsed (own transaction; called by {@link MaintenanceSlaJob}). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean flagSlaBreach(Long id) {
        MaintenanceRequest ticket = repository.findByIdForUpdate(id).orElse(null);
        if (ticket == null || !ticket.markSlaBreachedIfOverdue(clock.instant())) {
            return false;
        }
        events.publishEvent(new MaintenanceEvents.SlaBreached(ticket.getId(), ticket.getTicketNumber(),
                ticket.getOrganizationId(), ticket.getPropertyId(), ticket.getPriority()));
        return true;
    }

    private MaintenanceRequest lockForStaff(Long id) {
        MaintenanceRequest ticket = repository.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("MaintenanceRequest", id));
        accessGuard.requirePropertyAccess(ticket.getPropertyId());
        return ticket;
    }

    private void requireReadAccess(MaintenanceRequest ticket, CurrentUser user) {
        if (user.is(RoleName.TENANT)) {
            Long ownId = accessGuard.requireCurrentTenant().getId();
            if (!ownId.equals(ticket.getTenantId())) {
                throw new NotFoundException("MaintenanceRequest", ticket.getId());
            }
            return;
        }
        accessGuard.requirePropertyAccess(ticket.getPropertyId());
    }

    private void publishStatus(MaintenanceRequest ticket, MaintenanceStatus previous) {
        events.publishEvent(new MaintenanceEvents.StatusChanged(ticket.getId(), ticket.getTicketNumber(),
                ticket.getOrganizationId(), ticket.getPropertyId(), ticket.getTenantId(), ticket.getRaisedByUserId(),
                ticket.getAssignedToUserId(), previous, ticket.getStatus(), ticket.getPriority()));
    }
}
