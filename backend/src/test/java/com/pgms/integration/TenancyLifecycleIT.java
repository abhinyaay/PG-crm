package com.pgms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.pgms.integration.Api.Response;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** One resident's full journey through the public API, verifying side effects at each step. */
class TenancyLifecycleIT extends AbstractIntegrationTest {

    @Test
    void registerReserveCheckInPayTransferCheckOut() {
        Fixtures.Org org = fixtures.organization();
        fixtures.user(org, 2, "admin");
        String token = api.login(org.email("admin"));

        // structure through the API: property -> building -> floor -> room (beds generated)
        Response property = api.post("/api/v1/properties", token, """
                {"code":"LIFE-1","name":"Lifecycle House","type":"CO_LIVING",
                 "address":{"line1":"12 MG Road","city":"Bengaluru","state":"Karnataka","postalCode":"560001"}}""");
        assertThat(property.status()).isEqualTo(201);
        long propertyId = property.id();
        long buildingId = api.post("/api/v1/properties/" + propertyId + "/buildings", token,
                "{\"code\":\"A\",\"name\":\"Block A\"}").id();
        long floorId = api.post("/api/v1/properties/" + propertyId + "/buildings/" + buildingId + "/floors", token,
                "{\"floorNumber\":1}").id();
        Response room = api.post("/api/v1/rooms?propertyId=" + propertyId, token, "{\"floorId\":" + floorId
                + ",\"roomNumber\":\"101\",\"roomType\":\"DOUBLE\",\"capacity\":2,\"monthlyRent\":12000,\"securityDeposit\":24000}");
        assertThat(room.status()).isEqualTo(201);
        assertThat(room.body().get("beds")).hasSize(2);
        long bed1 = room.body().at("/beds/0/id").asLong();
        long bed2 = room.body().at("/beds/1/id").asLong();
        assertThat(api.post("/api/v1/rooms?propertyId=" + propertyId, token, "{\"floorId\":" + floorId
                + ",\"roomNumber\":\"102\",\"roomType\":\"SINGLE\",\"capacity\":3,\"monthlyRent\":1,\"securityDeposit\":1}")
                .status()).isEqualTo(422);

        Response tenant = api.post("/api/v1/tenants", token, "{\"propertyId\":" + propertyId
                + ",\"fullName\":\"Meera Iyer\",\"email\":\"meera@" + org.slug() + ".test\",\"phone\":\"+919812345670\","
                + "\"emergencyContacts\":[{\"name\":\"R Iyer\",\"relationship\":\"Father\",\"phone\":\"+919812345671\","
                + "\"primary\":true}]}");
        assertThat(tenant.status()).isEqualTo(201);
        long tenantId = tenant.id();

        String today = LocalDate.now().toString();
        Response booking = api.post("/api/v1/bookings", token, "{\"tenantId\":" + tenantId + ",\"bedId\":" + bed1
                + ",\"expectedCheckInDate\":\"" + today + "\"}");
        assertThat(booking.status()).isEqualTo(201);
        assertThat(fixtures.bedStatus(bed1)).isEqualTo("RESERVED");

        Response checkIn = api.post("/api/v1/allocations/check-in", token, "{\"tenantId\":" + tenantId + ",\"bookingId\":"
                + booking.id() + ",\"checkInDate\":\"" + today + "\"}");
        assertThat(checkIn.status()).isEqualTo(201);
        long allocationId = checkIn.body().at("/allocation/id").asLong();
        assertThat(fixtures.bedStatus(bed1)).isEqualTo("OCCUPIED");
        assertThat(checkIn.body().get("invoices")).hasSize(2); // deposit + (prorated) first month
        assertThat(api.get("/api/v1/bookings/" + booking.id(), token).body().get("status").asText()).isEqualTo("CHECKED_IN");

        JsonNode balance = api.get("/api/v1/tenants/" + tenantId + "/balance", token).body();
        BigDecimal outstanding = balance.get("outstanding").decimalValue();
        assertThat(outstanding).isGreaterThan(new BigDecimal("24000"));
        long depositInvoice = checkIn.body().get("invoices").get(0).get("id").asLong();
        assertThat(api.post("/api/v1/payments", token, "{\"invoiceId\":" + depositInvoice + ",\"amount\":24000,\"method\":\"UPI\"}",
                "Idempotency-Key", "life-" + System.nanoTime()).status()).isEqualTo(201);
        assertThat(api.get("/api/v1/tenants/" + tenantId + "/balance", token).body().get("depositHeld").decimalValue())
                .isEqualByComparingTo("24000");

        Response transfer = api.post("/api/v1/allocations/" + allocationId + "/transfer", token,
                "{\"targetBedId\":" + bed2 + ",\"transferDate\":\"" + today + "\"}");
        assertThat(transfer.status()).isEqualTo(200);
        assertThat(transfer.body().at("/previous/status").asText()).isEqualTo("TRANSFERRED");
        long newAllocation = transfer.body().at("/current/id").asLong();
        assertThat(fixtures.bedStatus(bed1)).isEqualTo("AVAILABLE");
        assertThat(fixtures.bedStatus(bed2)).isEqualTo("OCCUPIED");

        assertThat(api.post("/api/v1/allocations/" + newAllocation + "/notice", token,
                "{\"expectedCheckOutDate\":\"" + LocalDate.now().plusDays(10) + "\"}").status()).isEqualTo(200);
        assertThat(api.get("/api/v1/tenants/" + tenantId, token).body().get("status").asText()).isEqualTo("ON_NOTICE");

        Response checkOut = api.post("/api/v1/allocations/" + newAllocation + "/check-out", token, "{\"checkOutDate\":\""
                + today + "\",\"deductions\":[{\"description\":\"Broken chair\",\"amount\":1500}]}");
        assertThat(checkOut.status()).isEqualTo(200);
        JsonNode settlement = checkOut.body().get("settlement");
        assertThat(settlement.get("depositHeld").decimalValue()).isEqualByComparingTo("24000");
        assertThat(settlement.get("deductions").decimalValue()).isEqualByComparingTo("1500");
        BigDecimal expected = new BigDecimal("24000").subtract(settlement.get("outstandingDues").decimalValue())
                .subtract(new BigDecimal("1500"));
        assertThat(settlement.get("refundDue").decimalValue()).isEqualByComparingTo(expected.max(BigDecimal.ZERO));
        assertThat(fixtures.bedStatus(bed2)).isEqualTo("AVAILABLE");
        assertThat(api.get("/api/v1/tenants/" + tenantId, token).body().get("status").asText()).isEqualTo("MOVED_OUT");

        JsonNode history = api.get("/api/v1/tenants/" + tenantId + "/allocations", token).body();
        assertThat(history).hasSize(2);
        assertThat(api.get("/api/v1/audit-logs?entityType=ALLOCATION", token).body().get("totalElements").asLong())
                .isGreaterThanOrEqualTo(3);
        // notifications were queued in the same transactions (outbox)
        assertThat(fixtures.count("SELECT count(*) FROM notifications WHERE recipient_address = ?",
                "meera@" + org.slug() + ".test")).isGreaterThanOrEqualTo(4);
    }

    @Test
    void reservationCanBeCancelledAndBedIsReleased() {
        Fixtures.Org org = fixtures.organization();
        Fixtures.PropertyLayout layout = fixtures.property(org, 1);
        fixtures.user(org, 3, "manager");
        fixtures.assign(fixtures.count("SELECT id FROM users WHERE email = ?", org.email("manager")), layout.propertyId());
        String token = api.login(org.email("manager"));
        long tenant = fixtures.tenant(org, layout.propertyId());
        long bed = layout.bedIds().get(0);
        long booking = api.post("/api/v1/bookings", token, "{\"tenantId\":" + tenant + ",\"bedId\":" + bed
                + ",\"expectedCheckInDate\":\"" + LocalDate.now().plusDays(3) + "\",\"holdHours\":24}").id();

        assertThat(api.post("/api/v1/bookings/" + booking + "/cancel", token, "{\"reason\":\"Changed plans\"}").status())
                .isEqualTo(200);
        assertThat(fixtures.bedStatus(bed)).isEqualTo("AVAILABLE");
        assertThat(api.post("/api/v1/bookings/" + booking + "/cancel", token, "{\"reason\":\"again\"}").status()).isEqualTo(409);
    }

    @Test
    void onlyOnePrimaryEmergencyContactAlsoOnRegistration() {
        Fixtures.Org org = fixtures.organization();
        Fixtures.PropertyLayout layout = fixtures.property(org, 1);
        fixtures.user(org, 2, "admin");
        String token = api.login(org.email("admin"));
        Response response = api.post("/api/v1/tenants", token, "{\"propertyId\":" + layout.propertyId()
                + ",\"fullName\":\"Two Primaries\",\"phone\":\"+919812300001\",\"emergencyContacts\":["
                + "{\"name\":\"A\",\"relationship\":\"Father\",\"phone\":\"+919812300002\",\"primary\":true},"
                + "{\"name\":\"B\",\"relationship\":\"Mother\",\"phone\":\"+919812300003\",\"primary\":true}]}");
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.body().get("code").asText()).isEqualTo("MULTIPLE_PRIMARY_CONTACTS");
    }

    /** Deleting a resident's login through the user admin API must not block giving them a new one later. */
    @Test
    void portalAccountCanBeRecreatedAfterTheOldLoginWasDeleted() {
        Fixtures.Org org = fixtures.organization();
        Fixtures.PropertyLayout layout = fixtures.property(org, 1);
        fixtures.user(org, 2, "admin");
        String token = api.login(org.email("admin"));
        long tenant = fixtures.tenant(org, layout.propertyId());
        String body = "{\"initialPassword\":\"Passw0rd123\"}";

        Response first = api.post("/api/v1/tenants/" + tenant + "/portal-account", token, body);
        assertThat(first.status()).isEqualTo(200);
        long oldUser = first.body().get("userId").asLong();
        assertThat(api.post("/api/v1/tenants/" + tenant + "/portal-account", token, body).status()).isEqualTo(409);
        assertThat(api.delete("/api/v1/users/" + oldUser, token).status()).isEqualTo(204);

        Response second = api.post("/api/v1/tenants/" + tenant + "/portal-account", token, body);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.body().get("userId").asLong()).isNotEqualTo(oldUser);
        String email = second.body().get("email").asText();
        assertThat(api.get("/api/v1/tenants/me", api.login(email)).body().get("id").asLong()).isEqualTo(tenant);
    }
}
