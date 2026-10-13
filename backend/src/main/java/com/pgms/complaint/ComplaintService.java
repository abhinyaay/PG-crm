package com.pgms.complaint;

import com.pgms.audit.AuditService;
import com.pgms.common.api.PageResponse;
import com.pgms.common.api.SortValidator;
import com.pgms.common.domain.Priority;
import com.pgms.common.exception.BadRequestException;
import com.pgms.common.exception.BusinessRuleException;
import com.pgms.common.exception.ForbiddenException;
import com.pgms.common.exception.NotFoundException;
import com.pgms.common.lookup.DisplayNames;
import com.pgms.common.util.ReferenceNumberGenerator;
import com.pgms.complaint.ComplaintDtos.ActivityResponse;
import com.pgms.complaint.ComplaintDtos.CommentResponse;
import com.pgms.complaint.ComplaintDtos.ComplaintResponse;
import com.pgms.complaint.ComplaintDtos.CreateComplaintRequest;
import com.pgms.property.PropertyRepository;
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
public class ComplaintService {

    private static final Set<String> SORTABLE = Set.of("createdAt", "dueAt", "priority", "escalationLevel");
    private static final Set<ComplaintStatus> TENANT_ALLOWED = EnumSet.of(ComplaintStatus.CLOSED, ComplaintStatus.OPEN);

    private final ComplaintRepository repository;
    private final ComplaintCommentRepository commentRepository;
    private final ComplaintActivityRepository activityRepository;
    private final PropertyRepository propertyRepository;
    private final UserRepository userRepository;
    private final ReferenceNumberGenerator referenceNumbers;
    private final AccessGuard accessGuard;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final DisplayNames displayNames;
    private final Clock clock;

    public ComplaintService(ComplaintRepository repository, ComplaintCommentRepository commentRepository,
                            ComplaintActivityRepository activityRepository, PropertyRepository propertyRepository,
                            UserRepository userRepository, ReferenceNumberGenerator referenceNumbers, AccessGuard accessGuard,
                            AuditService auditService, ApplicationEventPublisher events, DisplayNames displayNames, Clock clock) {
        this.repository = repository;
        this.commentRepository = commentRepository;
        this.activityRepository = activityRepository;
        this.propertyRepository = propertyRepository;
        this.userRepository = userRepository;
        this.referenceNumbers = referenceNumbers;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
        this.events = events;
        this.displayNames = displayNames;
        this.clock = clock;
    }

    @Transactional
    public ComplaintResponse create(CreateComplaintRequest request) {
        CurrentUser user = accessGuard.currentUser();
        Long propertyId;
        Long tenantId = null;
        if (user.is(RoleName.TENANT)) {
            Tenant tenant = accessGuard.requireCurrentTenant();
            propertyId = tenant.getPropertyId();
            tenantId = tenant.getId();
        } else {
            if (request.propertyId() == null) {
                throw new BadRequestException("PROPERTY_REQUIRED", "propertyId is required");
            }
            propertyId = request.propertyId();
            accessGuard.requirePropertyAccess(propertyId);
        }
        Long organizationId = propertyRepository.findOrganizationIdById(propertyId)
                .orElseThrow(() -> new NotFoundException("Property", request.propertyId()));
        Instant now = clock.instant();
        Complaint complaint = repository.save(new Complaint(referenceNumbers.next(ReferenceNumberGenerator.Kind.COMPLAINT),
                organizationId, propertyId, tenantId, user.id(), request.category(), request.priority(), request.subject(),
                request.description(), now));
        activity(complaint, ComplaintActivityType.CREATED, null, ComplaintStatus.OPEN, user.id(), null, now);
        auditService.record(organizationId, "COMPLAINT_CREATED", "COMPLAINT", complaint.getId(),
                Map.of("category", request.category().name(), "priority", request.priority().name()));
        publish(complaint, ComplaintActivityType.CREATED, true);
        return respond(complaint);
    }

    @Transactional
    public ComplaintResponse assign(Long id, Long assigneeUserId) {
        Complaint complaint = lockForStaff(id);
        boolean eligible = userRepository.findActiveAssignedToProperty(complaint.getPropertyId(),
                        Set.of(RoleName.STAFF, RoleName.MANAGER)).stream()
                .anyMatch(u -> u.getId().equals(assigneeUserId))
                || userRepository.findActiveByOrganizationAndRoles(complaint.getOrganizationId(), Set.of(RoleName.PROPERTY_ADMIN))
                .stream().anyMatch(u -> u.getId().equals(assigneeUserId));
        if (!eligible) {
            throw new BusinessRuleException("INVALID_ASSIGNEE", "Assignee must be staff/manager of the property or an admin");
        }
        complaint.assignTo(assigneeUserId);
        activity(complaint, ComplaintActivityType.ASSIGNED, complaint.getStatus(), complaint.getStatus(),
                accessGuard.currentUser().id(), "Assigned to user " + assigneeUserId, clock.instant());
        auditService.record(complaint.getOrganizationId(), "COMPLAINT_ASSIGNED", "COMPLAINT", id,
                Map.of("assigneeUserId", assigneeUserId));
        publish(complaint, ComplaintActivityType.ASSIGNED, false);
        return respond(complaint);
    }

    @Transactional
    public ComplaintResponse changeStatus(Long id, ComplaintStatus target, String resolution) {
        CurrentUser user = accessGuard.currentUser();
        Complaint complaint = repository.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Complaint", id));
        requireReadAccess(complaint, user);
        if (user.is(RoleName.TENANT)) {
            boolean ownResolved = TENANT_ALLOWED.contains(target)
                    && (target == ComplaintStatus.CLOSED || complaint.getStatus() == ComplaintStatus.RESOLVED);
            if (!ownResolved) {
                throw new ForbiddenException("Tenants can only close a complaint or reopen a resolved one");
            }
        } else if (user.is(RoleName.STAFF) && !user.id().equals(complaint.getAssignedToUserId())) {
            throw new ForbiddenException("Staff can only update complaints assigned to them");
        }
        Instant now = clock.instant();
        ComplaintStatus previous = complaint.transitionTo(target, resolution, now);
        activity(complaint, ComplaintActivityType.STATUS_CHANGED, previous, target, user.id(), resolution, now);
        auditService.record(complaint.getOrganizationId(), "COMPLAINT_STATUS_CHANGED", "COMPLAINT", id,
                Map.of("from", previous.name(), "to", target.name()));
        publish(complaint, ComplaintActivityType.STATUS_CHANGED, true);
        return respond(complaint);
    }

    @Transactional
    public ComplaintResponse escalate(Long id, String reason) {
        CurrentUser user = accessGuard.currentUser();
        Complaint complaint = repository.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Complaint", id));
        requireReadAccess(complaint, user);
        if (user.is(RoleName.TENANT) && !complaint.isOverdue(clock.instant())) {
            throw new BusinessRuleException("NOT_OVERDUE", "Tenants can escalate only after the resolution deadline has passed");
        }
        doEscalate(complaint, user.id(), reason, false);
        return respond(complaint);
    }

    /** Automatic escalation of an overdue complaint (own transaction; called by {@link ComplaintEscalationJob}). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean autoEscalateIfOverdue(Long id) {
        Complaint complaint = repository.findByIdForUpdate(id).orElse(null);
        if (complaint == null || !complaint.isOverdue(clock.instant())
                || complaint.getEscalationLevel() >= Complaint.MAX_ESCALATION_LEVEL) {
            return false;
        }
        doEscalate(complaint, null, "Resolution deadline missed", true);
        return true;
    }

    @Transactional
    public CommentResponse addComment(Long id, String body, boolean internal) {
        CurrentUser user = accessGuard.currentUser();
        Complaint complaint = repository.findById(id).orElseThrow(() -> new NotFoundException("Complaint", id));
        requireReadAccess(complaint, user);
        if (internal && user.is(RoleName.TENANT)) {
            throw new ForbiddenException("Tenants cannot add internal notes");
        }
        Instant now = clock.instant();
        ComplaintComment comment = commentRepository.save(new ComplaintComment(id, user.id(), body, internal, now));
        activity(complaint, ComplaintActivityType.COMMENTED, null, null, user.id(), internal ? "internal note" : null, now);
        publish(complaint, ComplaintActivityType.COMMENTED, !internal);
        return CommentResponse.of(comment);
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> comments(Long id) {
        CurrentUser user = accessGuard.currentUser();
        Complaint complaint = repository.findById(id).orElseThrow(() -> new NotFoundException("Complaint", id));
        requireReadAccess(complaint, user);
        List<ComplaintComment> comments = user.is(RoleName.TENANT)
                ? commentRepository.findByComplaintIdAndInternalFalseOrderByCreatedAtAscIdAsc(id)
                : commentRepository.findByComplaintIdOrderByCreatedAtAscIdAsc(id);
        return comments.stream().map(CommentResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public List<ActivityResponse> history(Long id) {
        CurrentUser user = accessGuard.currentUser();
        Complaint complaint = repository.findById(id).orElseThrow(() -> new NotFoundException("Complaint", id));
        requireReadAccess(complaint, user);
        return activityRepository.findByComplaintIdOrderByCreatedAtAscIdAsc(id).stream()
                .filter(a -> !(user.is(RoleName.TENANT) && "internal note".equals(a.getDetails())))
                .map(ActivityResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public ComplaintResponse get(Long id) {
        Complaint complaint = repository.findById(id).orElseThrow(() -> new NotFoundException("Complaint", id));
        requireReadAccess(complaint, accessGuard.currentUser());
        return respond(complaint);
    }

    @Transactional(readOnly = true)
    public PageResponse<ComplaintResponse> search(Long propertyId, ComplaintStatus status, ComplaintCategory category,
                                                  Priority priority, Integer minEscalationLevel, boolean assignedToMe,
                                                  Pageable pageable) {
        CurrentUser user = accessGuard.currentUser();
        Pageable page = SortValidator.validate(pageable, SORTABLE, Sort.by(Sort.Direction.DESC, "createdAt"));
        Specification<Complaint> spec;
        if (user.is(RoleName.TENANT)) {
            Long ownId = accessGuard.requireCurrentTenant().getId();
            spec = (root, q, cb) -> cb.equal(root.get("tenantId"), ownId);
        } else {
            AccessScope scope = accessGuard.scopeFor(propertyId);
            if (scope.isEmpty()) {
                return PageResponse.from(Page.<Complaint>empty(page), x -> ComplaintResponse.of(x, DisplayNames.Index.EMPTY));
            }
            spec = scope.toSpecification();
        }
        if (assignedToMe) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("assignedToUserId"), user.id()));
        }
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        if (category != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("category"), category));
        }
        if (priority != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("priority"), priority));
        }
        if (minEscalationLevel != null) {
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("escalationLevel"), minEscalationLevel));
        }
        Page<Complaint> rows = repository.findAll(spec, page);
        DisplayNames.Index names = names(rows.getContent());
        return PageResponse.from(rows, x -> ComplaintResponse.of(x, names));
    }

    private void doEscalate(Complaint complaint, Long actorId, String reason, boolean automatic) {
        Instant now = clock.instant();
        ComplaintStatus previous = complaint.escalate(now);
        activity(complaint, ComplaintActivityType.ESCALATED, previous, ComplaintStatus.ESCALATED, actorId,
                "Level " + complaint.getEscalationLevel() + ": " + reason, now);
        auditService.record(complaint.getOrganizationId(), "COMPLAINT_ESCALATED", "COMPLAINT", complaint.getId(),
                Map.of("level", complaint.getEscalationLevel(), "automatic", automatic));
        events.publishEvent(new ComplaintEvents.ComplaintEscalated(complaint.getId(), complaint.getComplaintNumber(),
                complaint.getOrganizationId(), complaint.getPropertyId(), complaint.getEscalationLevel(), automatic));
    }

    private Complaint lockForStaff(Long id) {
        Complaint complaint = repository.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Complaint", id));
        accessGuard.requirePropertyAccess(complaint.getPropertyId());
        return complaint;
    }

    private void requireReadAccess(Complaint complaint, CurrentUser user) {
        if (user.is(RoleName.TENANT)) {
            if (!accessGuard.requireCurrentTenant().getId().equals(complaint.getTenantId())) {
                throw new NotFoundException("Complaint", complaint.getId());
            }
            return;
        }
        accessGuard.requirePropertyAccess(complaint.getPropertyId());
    }

    private void activity(Complaint complaint, ComplaintActivityType type, ComplaintStatus from, ComplaintStatus to,
                          Long actorId, String details, Instant at) {
        activityRepository.save(new ComplaintActivity(complaint.getId(), type, from, to, actorId, details, at));
    }

    private void publish(Complaint complaint, ComplaintActivityType activity, boolean visibleToTenant) {
        events.publishEvent(new ComplaintEvents.ComplaintUpdated(complaint.getId(), complaint.getComplaintNumber(),
                complaint.getOrganizationId(), complaint.getPropertyId(), complaint.getRaisedByUserId(),
                complaint.getAssignedToUserId(), activity, complaint.getStatus(), visibleToTenant));
    }

    private ComplaintResponse respond(Complaint row) {
        return ComplaintResponse.of(row, names(List.of(row)));
    }

    /** Tenant and user (raised by / assignee) names for a page in at most two queries. */
    private DisplayNames.Index names(List<Complaint> rows) {
        return displayNames.lookup(DisplayNames.ids(rows, Complaint::getTenantId),
                DisplayNames.ids(rows, Complaint::getRaisedByUserId, Complaint::getAssignedToUserId), Set.of());
    }
}
