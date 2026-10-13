package com.pgms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.pgms.support.BusinessDates;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class NotificationPreferencesIT extends AbstractIntegrationTest {

    @Autowired
    private Outbox outbox;

    @Test
    void residentPreferencesRespectMandatoryCellsAndDriveDelivery() {
        Fixtures.Org org = fixtures.organization();
        Fixtures.PropertyLayout layout = fixtures.property(org, 1);
        long manager = fixtures.user(org, 3, "manager");
        fixtures.assign(manager, layout.propertyId());
        long tenant = fixtures.tenant(org, layout.propertyId());
        fixtures.linkTenantUser(tenant, fixtures.user(org, 5, "resident"));
        String resident = api.login(org.email("resident"));

        JsonNode grid = api.get("/api/v1/notifications/preferences", resident).body();
        assertThat(cell(grid, "PAYMENT_RECEIVED", "IN_APP").get("mandatory").asBoolean()).isTrue();
        assertThat(cell(grid, "PAYMENT_RECEIVED", "IN_APP").get("enabled").asBoolean()).isTrue();
        assertThat(cell(grid, "BOOKING_CONFIRMED", "SMS").get("enabled").asBoolean()).isTrue();
        assertThat(cell(grid, "ROOM_TRANSFER", "SMS").get("enabled").asBoolean()).isFalse();
        assertThat(cell(grid, "MAINTENANCE_CREATED", "IN_APP")).isNull(); // staff-only type

        Api.Response mandatory = api.put("/api/v1/notifications/preferences", resident,
                "{\"items\":[{\"type\":\"PAYMENT_RECEIVED\",\"channel\":\"IN_APP\",\"enabled\":false}]}");
        assertThat(mandatory.status()).isEqualTo(422);
        assertThat(mandatory.body().get("code").asText()).isEqualTo("PREFERENCE_MANDATORY");
        Api.Response unavailable = api.put("/api/v1/notifications/preferences", resident,
                "{\"items\":[{\"type\":\"MAINTENANCE_CREATED\",\"channel\":\"IN_APP\",\"enabled\":true}]}");
        assertThat(unavailable.status()).isEqualTo(422);
        assertThat(unavailable.body().get("code").asText()).isEqualTo("PREFERENCE_NOT_AVAILABLE");
        assertThat(api.put("/api/v1/notifications/preferences", resident, "{\"items\":[]}").status()).isEqualTo(400);

        Api.Response updated = api.put("/api/v1/notifications/preferences", resident, "{\"items\":["
                + "{\"type\":\"BOOKING_CONFIRMED\",\"channel\":\"EMAIL\",\"enabled\":false},"
                + "{\"type\":\"ROOM_TRANSFER\",\"channel\":\"SMS\",\"enabled\":true},"
                + "{\"type\":\"PAYMENT_RECEIVED\",\"channel\":\"IN_APP\",\"enabled\":true}]}");
        assertThat(updated.status()).isEqualTo(200);
        assertThat(cell(updated.body(), "BOOKING_CONFIRMED", "EMAIL").get("enabled").asBoolean()).isFalse();
        assertThat(cell(updated.body(), "ROOM_TRANSFER", "SMS").get("enabled").asBoolean()).isTrue();
        // changing it again updates the same row
        assertThat(api.put("/api/v1/notifications/preferences", resident,
                "{\"items\":[{\"type\":\"ROOM_TRANSFER\",\"channel\":\"SMS\",\"enabled\":false}]}").status()).isEqualTo(200);
        assertThat(fixtures.count("SELECT count(*) FROM notification_preferences WHERE user_id = (SELECT user_id FROM tenants "
                + "WHERE id = ?)", tenant)).isEqualTo(2);

        long booking = reserve(api.login(org.email("manager")), tenant, layout.bedIds().get(0));
        assertThat(channelsFor(booking)).containsExactly("IN_APP", "SMS");
    }

    @Test
    void organizationAdminCanSwitchOffSmsForATypeButNotMandatoryCells() {
        Fixtures.Org org = fixtures.organization();
        Fixtures.PropertyLayout layout = fixtures.property(org, 1);
        fixtures.user(org, 2, "admin");
        long manager = fixtures.user(org, 3, "manager");
        fixtures.assign(manager, layout.propertyId());
        long tenant = fixtures.tenant(org, layout.propertyId());
        fixtures.linkTenantUser(tenant, fixtures.user(org, 5, "resident"));
        String admin = api.login(org.email("admin"));
        String path = "/api/v1/organizations/" + org.id() + "/notification-settings";

        JsonNode settings = api.get(path, admin).body();
        assertThat(cell(settings, "BOOKING_CONFIRMED", "SMS").get("enabled").asBoolean()).isTrue();
        assertThat(cell(settings, "PASSWORD_RESET_OTP", "EMAIL").get("mandatory").asBoolean()).isTrue();
        assertThat(cell(settings, "MAINTENANCE_CREATED", "IN_APP")).isNotNull();

        Api.Response off = api.put(path, admin, "{\"items\":[{\"type\":\"BOOKING_CONFIRMED\",\"channel\":\"SMS\",\"enabled\":false}]}");
        assertThat(off.status()).isEqualTo(200);
        assertThat(cell(off.body(), "BOOKING_CONFIRMED", "SMS").get("enabled").asBoolean()).isFalse();
        Api.Response mandatory = api.put(path, admin,
                "{\"items\":[{\"type\":\"PASSWORD_RESET_OTP\",\"channel\":\"SMS\",\"enabled\":false}]}");
        assertThat(mandatory.status()).isEqualTo(422);
        assertThat(mandatory.body().get("code").asText()).isEqualTo("PREFERENCE_MANDATORY");
        assertThat(api.put(path, admin, "{\"items\":[{\"type\":\"MOVE_IN\",\"channel\":\"PUSH\",\"enabled\":false}]}").status())
                .isEqualTo(422);

        // the resident sees the switch and their SMS opt-in no longer matters
        JsonNode grid = api.get("/api/v1/notifications/preferences", api.login(org.email("resident"))).body();
        assertThat(cell(grid, "BOOKING_CONFIRMED", "SMS").get("orgDisabled").asBoolean()).isTrue();
        long booking = reserve(api.login(org.email("manager")), tenant, layout.bedIds().get(0));
        assertThat(channelsFor(booking)).containsExactly("IN_APP", "EMAIL");

        // own organization only; admins only
        assertThat(api.get(path, api.login(org.email("manager"))).status()).isEqualTo(403);
        Fixtures.Org other = fixtures.organization();
        fixtures.user(other, 2, "admin");
        assertThat(api.get(path, api.login(other.email("admin"))).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/organizations/999999999/notification-settings", api.login("root@platform.test")).status())
                .isEqualTo(404);
        assertThat(api.get("/api/v1/audit-logs?action=NOTIFICATION_SETTINGS_UPDATED", admin).body().get("totalElements").asLong())
                .isEqualTo(1);
    }

    private long reserve(String token, long tenant, long bed) {
        Api.Response booking = api.post("/api/v1/bookings", token, "{\"tenantId\":" + tenant + ",\"bedId\":" + bed
                + ",\"expectedCheckInDate\":\"" + BusinessDates.today().plusDays(2) + "\",\"holdHours\":24}");
        assertThat(booking.status()).isEqualTo(201);
        return booking.id();
    }

    private List<Object> channelsFor(long bookingId) {
        return outbox.rows("SELECT channel FROM notifications WHERE reference_type = 'BOOKING' AND reference_id = ? ORDER BY id",
                bookingId).stream().map(r -> r.get("channel")).toList();
    }

    private static JsonNode cell(JsonNode items, String type, String channel) {
        return StreamSupport.stream(items.spliterator(), false)
                .filter(i -> i.get("type").asText().equals(type) && i.get("channel").asText().equals(channel))
                .findFirst().orElse(null);
    }
}
