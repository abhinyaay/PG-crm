package com.pgms.audit;

import com.pgms.common.web.RequestContext;
import com.pgms.security.CurrentUser;
import com.pgms.security.CurrentUserProvider;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records business-significant events (who did what to which entity, from where, under which correlation id).
 * Joins the caller's transaction: if the business change rolls back, so does its audit entry - no phantom history.
 */
@Service
public class AuditService {

    private final AuditLogRepository repository;
    private final CurrentUserProvider currentUserProvider;
    private final Clock clock;

    public AuditService(AuditLogRepository repository, CurrentUserProvider currentUserProvider, Clock clock) {
        this.repository = repository;
        this.currentUserProvider = currentUserProvider;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long organizationId, String action, String entityType, Long entityId, Map<String, ?> details) {
        Optional<CurrentUser> actor = currentUserProvider.current();
        repository.save(new AuditLog(
                organizationId,
                actor.map(CurrentUser::id).orElse(null),
                actor.map(CurrentUser::email).orElse("system"),
                action,
                entityType,
                entityId,
                details == null ? null : new LinkedHashMap<>(details),
                RequestContext.clientIp(),
                RequestContext.correlationId(),
                clock.instant()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long organizationId, String action, String entityType, Long entityId) {
        record(organizationId, action, entityType, entityId, Map.of());
    }
}
