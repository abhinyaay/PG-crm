package com.pgms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pgms.common.exception.BadRequestException;
import com.pgms.common.exception.ForbiddenException;
import com.pgms.common.exception.NotFoundException;
import com.pgms.property.PropertyAssignmentRepository;
import com.pgms.property.PropertyRepository;
import com.pgms.support.TestData;
import com.pgms.tenant.Tenant;
import com.pgms.tenant.TenantRepository;
import com.pgms.user.RoleName;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccessGuardTest {

    private static final long ORG = 10L;
    private static final long OTHER_ORG = 20L;
    private static final long PROPERTY = 100L;

    private CurrentUserProvider currentUser;
    private PropertyAssignmentRepository assignments;
    private TenantRepository tenants;
    private AccessGuard guard;

    @BeforeEach
    void setUp() {
        currentUser = mock(CurrentUserProvider.class);
        PropertyRepository properties = mock(PropertyRepository.class);
        assignments = mock(PropertyAssignmentRepository.class);
        tenants = mock(TenantRepository.class);
        when(properties.findOrganizationIdById(PROPERTY)).thenReturn(Optional.of(ORG));
        when(properties.findOrganizationIdById(999L)).thenReturn(Optional.empty());
        guard = new AccessGuard(currentUser, properties, assignments, tenants);
    }

    private void as(RoleName role, Long organizationId, long userId) {
        when(currentUser.require()).thenReturn(new CurrentUser(userId, organizationId, role, "u@example.com"));
    }

    @Test
    void superAdminSeesEverything() {
        as(RoleName.SUPER_ADMIN, null, 1);
        assertThatCode(() -> guard.requirePropertyAccess(PROPERTY)).doesNotThrowAnyException();
        assertThat(guard.scopeFor(null)).isEqualTo(AccessScope.global());
    }

    @Test
    void propertyAdminOfAnotherOrganizationGets404NotFound() {
        as(RoleName.PROPERTY_ADMIN, OTHER_ORG, 2);
        assertThatThrownBy(() -> guard.requirePropertyAccess(PROPERTY)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void propertyAdminSeesWholeOrganization() {
        as(RoleName.PROPERTY_ADMIN, ORG, 2);
        guard.requirePropertyAccess(PROPERTY);
        AccessScope scope = guard.scopeFor(null);
        assertThat(scope.organizationId()).isEqualTo(ORG);
        assertThat(scope.restrictedToProperties()).isFalse();
    }

    @Test
    void managerNeedsAnAssignment() {
        as(RoleName.MANAGER, ORG, 3);
        when(assignments.existsByPropertyIdAndUserId(PROPERTY, 3L)).thenReturn(false);
        assertThatThrownBy(() -> guard.requirePropertyAccess(PROPERTY)).isInstanceOf(ForbiddenException.class);
        when(assignments.existsByPropertyIdAndUserId(PROPERTY, 3L)).thenReturn(true);
        assertThatCode(() -> guard.requirePropertyAccess(PROPERTY)).doesNotThrowAnyException();
    }

    @Test
    void managerScopeIsTheAssignedPropertySet() {
        as(RoleName.STAFF, ORG, 4);
        when(assignments.findPropertyIdsByUserId(4L)).thenReturn(Set.of());
        assertThat(guard.scopeFor(null).isEmpty()).isTrue();
    }

    @Test
    void tenantOnlyReachesOwnProperty() {
        as(RoleName.TENANT, ORG, 5);
        Tenant tenant = new Tenant(ORG, 555L, "T", null, "+919900000000");
        when(tenants.findByUserId(5L)).thenReturn(Optional.of(tenant));
        assertThatThrownBy(() -> guard.requirePropertyAccess(PROPERTY)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> guard.scopeFor(null)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void unknownPropertyIs404() {
        as(RoleName.SUPER_ADMIN, null, 1);
        assertThatThrownBy(() -> guard.requirePropertyAccess(999L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void organizationForCreate() {
        as(RoleName.SUPER_ADMIN, null, 1);
        assertThatThrownBy(() -> guard.resolveOrganizationForCreate(null)).isInstanceOf(BadRequestException.class);
        assertThat(guard.resolveOrganizationForCreate(OTHER_ORG)).isEqualTo(OTHER_ORG);
        as(RoleName.PROPERTY_ADMIN, ORG, 2);
        assertThat(guard.resolveOrganizationForCreate(null)).isEqualTo(ORG);
        assertThatThrownBy(() -> guard.resolveOrganizationForCreate(OTHER_ORG)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void tenantProfileLookup() {
        as(RoleName.MANAGER, ORG, 3);
        assertThatThrownBy(() -> guard.requireCurrentTenant()).isInstanceOf(ForbiddenException.class);
        as(RoleName.TENANT, ORG, 6);
        Tenant own = TestData.withId(new Tenant(ORG, PROPERTY, "x", null, "+919900000009"), 1L);
        when(tenants.findByUserId(6L)).thenReturn(Optional.of(own));
        assertThat(guard.requireCurrentTenant()).isSameAs(own);
        assertThat(guard.isTenant()).isTrue();
    }
}
