package com.pgms.security;

import com.pgms.common.exception.BadRequestException;
import com.pgms.common.exception.ForbiddenException;
import com.pgms.common.exception.NotFoundException;
import com.pgms.property.PropertyAssignmentRepository;
import com.pgms.property.PropertyRepository;
import com.pgms.tenant.Tenant;
import com.pgms.tenant.TenantRepository;
import com.pgms.user.RoleName;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Central place for data-level authorization (who may touch which organization / property / tenant).
 * Role checks ("is a MANAGER") happen declaratively with {@code @PreAuthorize}; this class answers
 * "is it <em>this</em> manager's property". Cross-organization access is reported as 404 so that resource
 * existence in other organizations is not disclosed; same-organization but unassigned access is a 403.
 */
@Component
public class AccessGuard {

    private final CurrentUserProvider currentUserProvider;
    private final PropertyRepository propertyRepository;
    private final PropertyAssignmentRepository assignmentRepository;
    private final TenantRepository tenantRepository;

    public AccessGuard(CurrentUserProvider currentUserProvider, PropertyRepository propertyRepository,
                       PropertyAssignmentRepository assignmentRepository, TenantRepository tenantRepository) {
        this.currentUserProvider = currentUserProvider;
        this.propertyRepository = propertyRepository;
        this.assignmentRepository = assignmentRepository;
        this.tenantRepository = tenantRepository;
    }

    public CurrentUser currentUser() {
        return currentUserProvider.require();
    }

    public void requirePropertyAccess(Long propertyId) {
        CurrentUser user = currentUser();
        Long organizationId = propertyRepository.findOrganizationIdById(propertyId)
                .orElseThrow(() -> new NotFoundException("Property", propertyId));
        if (user.isSuperAdmin()) {
            return;
        }
        if (!Objects.equals(organizationId, user.organizationId())) {
            throw new NotFoundException("Property", propertyId);
        }
        switch (user.role()) {
            case PROPERTY_ADMIN -> {
                // whole organization
            }
            case MANAGER, STAFF -> {
                if (!assignmentRepository.existsByPropertyIdAndUserId(propertyId, user.id())) {
                    throw new ForbiddenException("You are not assigned to property " + propertyId);
                }
            }
            case TENANT -> {
                Tenant tenant = requireCurrentTenant();
                if (!tenant.getPropertyId().equals(propertyId)) {
                    throw new ForbiddenException("Tenants can only access their own property");
                }
            }
            default -> throw new ForbiddenException("Unsupported role");
        }
    }

    public void requireOrganizationAccess(Long organizationId) {
        CurrentUser user = currentUser();
        if (!user.isSuperAdmin() && !Objects.equals(organizationId, user.organizationId())) {
            throw new NotFoundException("Organization", organizationId);
        }
    }

    /**
     * Resolves which properties the caller may list, optionally narrowed to one requested property.
     * Not meant for TENANT callers - tenant-facing queries filter by the tenant's own id instead.
     */
    public AccessScope scopeFor(Long requestedPropertyId) {
        CurrentUser user = currentUser();
        if (requestedPropertyId != null) {
            requirePropertyAccess(requestedPropertyId);
            Long organizationId = user.isSuperAdmin() ? null : user.organizationId();
            return new AccessScope(organizationId, Set.of(requestedPropertyId));
        }
        return switch (user.role()) {
            case SUPER_ADMIN -> AccessScope.global();
            case PROPERTY_ADMIN -> new AccessScope(user.organizationId(), null);
            case MANAGER, STAFF -> new AccessScope(user.organizationId(), assignmentRepository.findPropertyIdsByUserId(user.id()));
            case TENANT -> throw new ForbiddenException("Not available for tenant accounts");
        };
    }

    /**
     * Organization in which a new record is created: platform admins must name it explicitly,
     * everybody else is pinned to their own organization.
     */
    public Long resolveOrganizationForCreate(Long requestedOrganizationId) {
        CurrentUser user = currentUser();
        if (user.isSuperAdmin()) {
            if (requestedOrganizationId == null) {
                throw new BadRequestException("ORGANIZATION_REQUIRED", "organizationId is required for platform administrators");
            }
            return requestedOrganizationId;
        }
        if (requestedOrganizationId != null && !requestedOrganizationId.equals(user.organizationId())) {
            throw new ForbiddenException("Cannot create records in another organization");
        }
        return user.organizationId();
    }

    public boolean isTenant() {
        return currentUser().is(RoleName.TENANT);
    }

    /** The resident profile linked to the calling TENANT account. */
    public Tenant requireCurrentTenant() {
        CurrentUser user = currentUser();
        if (!user.is(RoleName.TENANT)) {
            throw new ForbiddenException("Only tenant accounts have a tenant profile");
        }
        return tenantRepository.findByUserId(user.id())
                .orElseThrow(() -> new ForbiddenException("No tenant profile is linked to this account"));
    }
}
