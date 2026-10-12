package com.pgms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.pgms.integration.Api.Response;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Platform/organization administration: organizations, users, assignments, properties and rooms. */
class AdministrationIT extends AbstractIntegrationTest {

    @Test
    void superAdminOnboardsAnOrganizationAndItsAdmin() {
        String root = api.login("root@platform.test");
        String slug = "acme-" + System.nanoTime();
        Response org = api.post("/api/v1/organizations", root,
                "{\"name\":\"Acme Stays\",\"slug\":\"" + slug + "\",\"contactEmail\":\"ops@acme.test\"}");
        assertThat(org.status()).isEqualTo(201);
        assertThat(api.post("/api/v1/organizations", root, "{\"name\":\"Dup\",\"slug\":\"" + slug + "\"}").status())
                .isEqualTo(409);

        Response admin = api.post("/api/v1/users", root, "{\"email\":\"owner@" + slug + ".test\",\"password\":\"Passw0rd123\","
                + "\"fullName\":\"Owner\",\"role\":\"PROPERTY_ADMIN\",\"organizationId\":" + org.id() + "}");
        assertThat(admin.status()).isEqualTo(201);
        String ownerToken = api.login("owner@" + slug + ".test");
        assertThat(api.get("/api/v1/organizations/" + org.id(), ownerToken).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/organizations", ownerToken).status()).isEqualTo(403);

        // suspension blocks the organization's logins
        assertThat(api.post("/api/v1/organizations/" + org.id() + "/status", root, "{}").status()).isEqualTo(405);
        assertThat(api.patch("/api/v1/organizations/" + org.id() + "/status", root, "{\"status\":\"SUSPENDED\"}").status())
                .isEqualTo(200);
        Response login = api.post("/api/v1/auth/login", null, "{\"email\":\"owner@" + slug + ".test\",\"password\":\"Passw0rd123\"}");
        assertThat(login.body().get("code").asText()).isEqualTo("ORGANIZATION_SUSPENDED");
    }

    @Test
    void propertyAdminManagesStaffAndAssignments() {
        Fixtures.Org org = fixtures.organization();
        Fixtures.PropertyLayout layout = fixtures.property(org, 1);
        fixtures.user(org, 2, "admin");
        String token = api.login(org.email("admin"));

        Response manager = api.post("/api/v1/users", token, "{\"email\":\"" + org.email("new.manager") + "\","
                + "\"password\":\"Passw0rd123\",\"fullName\":\"New Manager\",\"phone\":\"+919876543210\",\"role\":\"MANAGER\","
                + "\"propertyIds\":[" + layout.propertyId() + "]}");
        assertThat(manager.status()).isEqualTo(201);
        assertThat(manager.body().get("propertyIds")).hasSize(1);
        assertThat(api.post("/api/v1/users", token, "{\"email\":\"" + org.email("new.manager") + "\","
                + "\"password\":\"Passw0rd123\",\"fullName\":\"Dup\",\"role\":\"STAFF\"}").status()).isEqualTo(409);
        Response weak = api.post("/api/v1/users", token, "{\"email\":\"" + org.email("weak") + "\","
                + "\"password\":\"short\",\"fullName\":\"Weak\",\"role\":\"STAFF\"}");
        assertThat(weak.status()).isEqualTo(400);

        long staff = api.post("/api/v1/users", token, "{\"email\":\"" + org.email("new.staff") + "\","
                + "\"password\":\"Passw0rd123\",\"fullName\":\"New Staff\",\"role\":\"STAFF\"}").id();
        assertThat(api.post("/api/v1/properties/" + layout.propertyId() + "/assignments", token, "{\"userId\":" + staff + "}")
                .status()).isEqualTo(201);
        assertThat(api.post("/api/v1/properties/" + layout.propertyId() + "/assignments", token, "{\"userId\":" + staff + "}")
                .status()).isEqualTo(409);
        assertThat(api.get("/api/v1/properties/" + layout.propertyId() + "/assignments", token).body()).hasSize(2);

        JsonNode page = api.get("/api/v1/users?role=STAFF&q=new", token).body();
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        assertThat(api.post("/api/v1/users/" + staff + "/reset-password", token, "{\"newPassword\":\"Another123\"}").status())
                .isEqualTo(204);
        assertThat(api.post("/api/v1/users/" + staff + "/deactivate", token, null).body().get("status").asText())
                .isEqualTo("INACTIVE");
        assertThat(api.post("/api/v1/users/" + staff + "/activate", token, null).body().get("status").asText()).isEqualTo("ACTIVE");
        assertThat(api.delete("/api/v1/properties/" + layout.propertyId() + "/assignments/" + staff, token).status())
                .isEqualTo(204);
        assertThat(api.delete("/api/v1/users/" + staff, token).status()).isEqualTo(204);
        assertThat(api.get("/api/v1/users/" + staff, token).status()).isEqualTo(404);
    }

    @Test
    void roomCapacityChangesAndBedMaintenance() {
        Fixtures.Org org = fixtures.organization();
        Fixtures.PropertyLayout layout = fixtures.property(org, 1);
        fixtures.user(org, 2, "admin");
        String token = api.login(org.email("admin"));
        long roomId = layout.roomIds().get(0);

        Response grown = api.put("/api/v1/rooms/" + roomId, token, "{\"roomType\":\"TRIPLE\",\"capacity\":3,"
                + "\"monthlyRent\":8000,\"securityDeposit\":16000,\"airConditioned\":true,\"attachedBathroom\":true}");
        assertThat(grown.status()).isEqualTo(200);
        assertThat(grown.body().get("beds")).hasSize(3);
        assertThat(grown.body().at("/beds/2/bedCode").asText()).isEqualTo("C");

        long bed = layout.bedIds().get(0);
        assertThat(api.patch("/api/v1/beds/" + bed + "/status", token, "{\"status\":\"MAINTENANCE\"}").status()).isEqualTo(200);
        assertThat(api.patch("/api/v1/beds/" + bed + "/status", token, "{\"status\":\"OCCUPIED\"}").status()).isEqualTo(422);
        assertThat(api.get("/api/v1/beds?propertyId=" + layout.propertyId() + "&status=AVAILABLE", token).body()
                .get("totalElements").asLong()).isEqualTo(2);
        Response shrunk = api.put("/api/v1/rooms/" + roomId, token, "{\"roomType\":\"SINGLE\",\"capacity\":1,"
                + "\"monthlyRent\":15000,\"securityDeposit\":30000,\"airConditioned\":true,\"attachedBathroom\":true}");
        assertThat(shrunk.body().get("beds")).hasSize(1);
        assertThat(api.get("/api/v1/rooms?propertyId=" + layout.propertyId(), token).body().get("totalElements").asLong())
                .isEqualTo(1);
        assertThat(api.patch("/api/v1/properties/" + layout.propertyId() + "/status", token, "{\"status\":\"UNDER_RENOVATION\"}")
                .body().get("status").asText()).isEqualTo("UNDER_RENOVATION");
        assertThat(api.get("/api/v1/properties/" + layout.propertyId(), token).body().get("status").asText())
                .isEqualTo("UNDER_RENOVATION"); // cache was evicted on update
    }

    @Test
    void pageSizeIsCappedAndPagesAreStable() {
        Fixtures.Org org = fixtures.organization();
        Fixtures.PropertyLayout layout = fixtures.property(org, 3);
        fixtures.user(org, 2, "admin");
        String token = api.login(org.email("admin"));
        assertThat(api.get("/api/v1/beds?size=100000", token).body().get("size").asInt()).isEqualTo(100);

        // six beds, three of each code: paging over the non-unique sort key returns every bed exactly once
        Set<Long> seen = new HashSet<>();
        for (int page = 0; page < 3; page++) {
            api.get("/api/v1/beds?propertyId=" + layout.propertyId() + "&sort=bedCode&size=2&page=" + page, token).body()
                    .get("content").forEach(bed -> seen.add(bed.get("id").asLong()));
        }
        assertThat(seen).containsExactlyInAnyOrderElementsOf(layout.bedIds());
    }
}
