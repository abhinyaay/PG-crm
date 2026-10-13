package com.pgms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pgms.support.BusinessDates;
import org.junit.jupiter.api.Test;

/** Role checks (@PreAuthorize) and data-level scoping (AccessGuard) over real HTTP requests. */
class AccessControlIT extends AbstractIntegrationTest {

    @Test
    void managerSeesOnlyAssignedPropertiesAndOtherOrganizationsAreInvisible() {
        Fixtures.Org org = fixtures.organization();
        Fixtures.Org otherOrg = fixtures.organization();
        Fixtures.PropertyLayout assigned = fixtures.property(org, 1);
        Fixtures.PropertyLayout notAssigned = fixtures.property(org, 1);
        Fixtures.PropertyLayout foreign = fixtures.property(otherOrg, 1);
        long manager = fixtures.user(org, 3, "manager");
        fixtures.assign(manager, assigned.propertyId());
        String token = api.login(org.email("manager"));

        assertThat(api.get("/api/v1/properties/" + assigned.propertyId(), token).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/properties/" + notAssigned.propertyId(), token).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/properties/" + foreign.propertyId(), token).status()).isEqualTo(404);

        var list = api.get("/api/v1/properties", token).body();
        assertThat(list.get("totalElements").asLong()).isEqualTo(1);
        assertThat(api.get("/api/v1/beds?propertyId=" + foreign.propertyId(), token).status()).isEqualTo(404);
    }

    @Test
    void propertyAdminCannotTouchAnotherOrganization() {
        Fixtures.Org org = fixtures.organization();
        Fixtures.Org otherOrg = fixtures.organization();
        Fixtures.PropertyLayout foreign = fixtures.property(otherOrg, 1);
        fixtures.user(org, 2, "admin");
        long foreignStaff = fixtures.user(otherOrg, 4, "staff");
        String token = api.login(org.email("admin"));

        assertThat(api.get("/api/v1/properties/" + foreign.propertyId() + "/occupancy", token).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/users/" + foreignStaff, token).status()).isEqualTo(404);
        // cannot escalate privileges by creating a platform admin
        String body = "{\"email\":\"x@" + org.slug() + ".test\",\"password\":\"Passw0rd123\","
                + "\"fullName\":\"X\",\"role\":\"PROPERTY_ADMIN\"}";
        assertThat(api.post("/api/v1/users", token, body).status()).isEqualTo(403);
    }

    @Test
    void roleRestrictedEndpoints() {
        Fixtures.Org org = fixtures.organization();
        Fixtures.PropertyLayout layout = fixtures.property(org, 1);
        long staff = fixtures.user(org, 4, "staff");
        fixtures.assign(staff, layout.propertyId());
        long tenantUser = fixtures.user(org, 5, "resident");
        fixtures.linkTenantUser(fixtures.tenant(org, layout.propertyId()), tenantUser);
        String staffToken = api.login(org.email("staff"));
        String tenantToken = api.login(org.email("resident"));

        assertThat(api.get("/api/v1/users", staffToken).status()).isEqualTo(403);
        // note: method security runs after body validation, so the probe uses a well-formed body
        assertThat(api.post("/api/v1/bookings", staffToken, "{\"tenantId\":1,\"bedId\":"
                + layout.bedIds().get(0) + ",\"expectedCheckInDate\":\"" + BusinessDates.today() + "\"}").status())
                .isEqualTo(403);
        assertThat(api.get("/api/v1/properties", tenantToken).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/audit-logs", tenantToken).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/tenants/me", tenantToken).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/dashboard", tenantToken).body().has("outstanding")).isTrue();
        assertThat(api.get("/api/v1/dashboard", staffToken).body().has("assignedOpen")).isTrue();
    }

    @Test
    void tenantCannotReadAnotherTenantsData() {
        Fixtures.Org org = fixtures.organization();
        Fixtures.PropertyLayout layout = fixtures.property(org, 1);
        long me = fixtures.tenant(org, layout.propertyId());
        long other = fixtures.tenant(org, layout.propertyId());
        long user = fixtures.user(org, 5, "me");
        fixtures.linkTenantUser(me, user);
        String token = api.login(org.email("me"));

        assertThat(api.get("/api/v1/tenants/" + me, token).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/tenants/" + other, token).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/tenants/" + other + "/balance", token).status()).isEqualTo(404);
    }
}
