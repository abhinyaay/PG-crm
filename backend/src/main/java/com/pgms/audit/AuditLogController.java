package com.pgms.audit;

import com.pgms.common.api.PageResponse;
import com.pgms.common.api.SortValidator;
import com.pgms.security.AccessGuard;
import com.pgms.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-logs")
@Tag(name = "Audit")
public class AuditLogController {

    private static final Set<String> SORTABLE = Set.of("createdAt");

    private final AuditLogRepository repository;
    private final AccessGuard accessGuard;

    public AuditLogController(AuditLogRepository repository, AccessGuard accessGuard) {
        this.repository = repository;
        this.accessGuard = accessGuard;
    }

    public record AuditLogResponse(Long id, Long organizationId, Long actorUserId, String actorEmail, String action,
                                   String entityType, Long entityId, Map<String, Object> details, String ipAddress,
                                   String correlationId, Instant createdAt) {
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN')")
    @Transactional(readOnly = true)
    @Operation(summary = "Search the audit trail of the caller's organization")
    public PageResponse<AuditLogResponse> search(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) Long entityId,
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            Pageable pageable) {
        CurrentUser user = accessGuard.currentUser();
        Specification<AuditLog> spec = (root, q, cb) -> cb.conjunction();
        if (!user.isSuperAdmin()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("organizationId"), user.organizationId()));
        }
        if (entityType != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("entityType"), entityType));
        }
        if (entityId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("entityId"), entityId));
        }
        if (actorUserId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("actorUserId"), actorUserId));
        }
        if (action != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("action"), action));
        }
        if (from != null) {
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        }
        if (to != null) {
            spec = spec.and((root, q, cb) -> cb.lessThan(root.get("createdAt"), to));
        }
        Pageable page = SortValidator.validate(pageable, SORTABLE, Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.from(repository.findAll(spec, page), a -> new AuditLogResponse(a.getId(), a.getOrganizationId(),
                a.getActorUserId(), a.getActorEmail(), a.getAction(), a.getEntityType(), a.getEntityId(), a.getDetails(),
                a.getIpAddress(), a.getCorrelationId(), a.getCreatedAt()));
    }
}
