package com.pgms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.pgms.integration.Api.Response;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Individual beds: add / rename / reprice / remove, capacity consistency, and per-bed prices in booking flows. */
class BedManagementIT extends AbstractIntegrationTest {

    /** The API validates dates in the business time zone; the JVM running the tests may be in UTC. */
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    private Fixtures.Org org;
    private Fixtures.PropertyLayout layout;
    private String token;
    private long roomId;

    @BeforeEach
    void setUp() {
        org = fixtures.organization();
        layout = fixtures.property(org, 1);
        fixtures.assign(fixtures.user(org, 3, "manager"), layout.propertyId());
        token = api.login(org.email("manager"));
        roomId = layout.roomIds().get(0);
    }

    private Response addBed(long room, String json) {
        return api.post("/api/v1/rooms/" + room + "/beds", token, json);
    }

    private JsonNode room(long id) {
        return api.get("/api/v1/rooms/" + id, token).body();
    }

    private Response createSpace(String json) {
        return api.post("/api/v1/rooms?propertyId=" + layout.propertyId(), token,
                "{\"floorId\":" + layout.floorId() + "," + json + "}");
    }

    @Test
    void bedsAreAddedRenamedRepricedAndRemovedKeepingCapacityInStep() {
        Response w1 = addBed(roomId, "{\"bedCode\":\"W1\",\"label\":\"Window\",\"monthlyRentOverride\":7500,\"posX\":1,\"posY\":2}");
        assertThat(w1.status()).isEqualTo(201);
        assertThat(w1.body().get("bedCode").asText()).isEqualTo("W1");
        assertThat(w1.body().get("label").asText()).isEqualTo("Window");
        assertThat(w1.body().get("monthlyRent").decimalValue()).isEqualByComparingTo("7500");
        assertThat(w1.body().get("monthlyRentOverride").decimalValue()).isEqualByComparingTo("7500");
        assertThat(w1.body().get("securityDeposit").decimalValue()).isEqualByComparingTo("18000"); // room price, no override
        assertThat(w1.body().get("posY").asInt()).isEqualTo(2);
        long w1Id = w1.id();
        assertThat(room(roomId).get("capacity").asInt()).isEqualTo(3);
        assertThat(room(roomId).get("roomType").asText()).isEqualTo("TRIPLE");

        assertThat(addBed(roomId, "{\"bedCode\":\"W1\"}").body().get("code").asText()).isEqualTo("BED_EXISTS");
        assertThat(addBed(roomId, "{\"bedCode\":\"bad code!\"}").status()).isEqualTo(400);
        assertThat(addBed(roomId, "{\"posX\":1}").body().get("code").asText()).isEqualTo("INVALID_POSITION");
        Response generated = addBed(roomId, "{}");
        assertThat(generated.body().get("bedCode").asText()).isEqualTo("C");
        assertThat(room(roomId).get("capacity").asInt()).isEqualTo(4);
        assertThat(room(roomId).get("roomType").asText()).isEqualTo("QUAD");

        Response renamed = api.patch("/api/v1/beds/" + w1Id, token,
                "{\"bedCode\":\"Window-2\",\"securityDepositOverride\":9000,\"clear\":[\"LABEL\"]}");
        assertThat(renamed.status()).isEqualTo(200);
        assertThat(renamed.body().get("bedCode").asText()).isEqualTo("Window-2");
        assertThat(renamed.body().has("label")).isFalse();
        assertThat(renamed.body().get("monthlyRentOverride").decimalValue()).isEqualByComparingTo("7500"); // untouched
        assertThat(renamed.body().get("securityDeposit").decimalValue()).isEqualByComparingTo("9000");
        assertThat(api.patch("/api/v1/beds/" + w1Id, token, "{\"bedCode\":\"A\"}").body().get("code").asText())
                .isEqualTo("BED_EXISTS");
        assertThat(api.patch("/api/v1/beds/" + w1Id, token, "{\"label\":\"x\",\"clear\":[\"LABEL\"]}").status()).isEqualTo(400);

        // bed search shows and filters on the effective price
        JsonNode cheap = api.get("/api/v1/beds?roomId=" + roomId + "&maxRent=8000", token).body();
        assertThat(cheap.get("content")).hasSize(1);
        assertThat(cheap.at("/content/0/bedCode").asText()).isEqualTo("Window-2");
        assertThat(cheap.at("/content/0/monthlyRent").decimalValue()).isEqualByComparingTo("7500");

        // the override flows into check-in: allocation snapshot and both move-in invoices
        long tenant = fixtures.tenant(org, layout.propertyId());
        Response checkIn = api.post("/api/v1/allocations/check-in", token, "{\"tenantId\":" + tenant + ",\"bedId\":" + w1Id
                + ",\"checkInDate\":\"" + TODAY + "\"}");
        assertThat(checkIn.status()).isEqualTo(201);
        JsonNode allocation = checkIn.body().get("allocation");
        assertThat(allocation.get("monthlyRent").decimalValue()).isEqualByComparingTo("7500");
        assertThat(allocation.get("securityDeposit").decimalValue()).isEqualByComparingTo("9000");
        assertThat(allocation.get("bedCode").asText()).isEqualTo("Window-2");
        assertThat(allocation.get("roomNumber").asText()).isEqualTo("101");
        assertThat(allocation.get("tenantName").asText()).startsWith("Tenant ");
        for (JsonNode invoice : checkIn.body().get("invoices")) {
            if (invoice.get("type").asText().equals("SECURITY_DEPOSIT")) {
                assertThat(invoice.get("totalAmount").decimalValue()).isEqualByComparingTo("9000");
            } else {
                assertThat(invoice.get("totalAmount").decimalValue()).isLessThanOrEqualTo(new BigDecimal("7500"));
            }
        }
        assertThat(fixtures.decimal("SELECT monthly_rent FROM allocations WHERE id = ?", allocation.get("id").asLong()))
                .isEqualByComparingTo("7500");
        assertThat(fixtures.decimal("SELECT total_amount FROM invoices WHERE allocation_id = ? AND invoice_type = 'RENT'",
                allocation.get("id").asLong())).isPositive().isLessThanOrEqualTo(new BigDecimal("7500"));

        // an occupied bed cannot be removed; free ones can, and capacity follows
        Response occupied = api.delete("/api/v1/beds/" + w1Id, token);
        assertThat(occupied.status()).isEqualTo(409);
        assertThat(occupied.body().get("code").asText()).isEqualTo("BED_IN_USE");
        assertThat(api.delete("/api/v1/beds/" + generated.id(), token).status()).isEqualTo(204);
        JsonNode after = room(roomId);
        assertThat(after.get("capacity").asInt()).isEqualTo(3);
        assertThat(after.get("roomType").asText()).isEqualTo("TRIPLE");
        assertThat(after.get("beds")).hasSize(3);
        assertThat(fixtures.count("SELECT count(*) FROM beds WHERE room_id = ? AND deleted_at IS NULL", roomId)).isEqualTo(3);
        assertThat(api.get("/api/v1/beds/" + generated.id(), token).status()).isEqualTo(404);
    }

    @Test
    void lastBedOfARoomAndBedsInBedlessSpacesAreRefused() {
        Response single = createSpace("\"roomNumber\":\"201\",\"roomType\":\"SINGLE\",\"capacity\":1,\"monthlyRent\":12000,"
                + "\"securityDeposit\":12000");
        long onlyBed = single.body().at("/beds/0/id").asLong();
        Response last = api.delete("/api/v1/beds/" + onlyBed, token);
        assertThat(last.status()).isEqualTo(422);
        assertThat(last.body().get("code").asText()).isEqualTo("LAST_BED_IN_ROOM");

        Response kitchen = createSpace("\"roomNumber\":\"K1\",\"spaceKind\":\"KITCHEN\"");
        Response noBeds = addBed(kitchen.id(), "{}");
        assertThat(noBeds.status()).isEqualTo(422);
        assertThat(noBeds.body().get("code").asText()).isEqualTo("SPACE_CANNOT_HOLD_BEDS");

        // a hall starts empty and may lose all of its beds again
        Response hall = createSpace("\"roomNumber\":\"H1\",\"spaceKind\":\"DORMITORY\",\"monthlyRent\":4000,\"securityDeposit\":4000");
        assertThat(hall.body().get("capacity").asInt()).isZero();
        long bed = addBed(hall.id(), "{\"bedCode\":\"D1\"}").id();
        assertThat(room(hall.id()).get("capacity").asInt()).isEqualTo(1);
        assertThat(api.delete("/api/v1/beds/" + bed, token).status()).isEqualTo(204);
        assertThat(room(hall.id()).get("capacity").asInt()).isZero();

        // the classic update endpoint keeps working on custom codes
        Response grown = api.put("/api/v1/rooms/" + hall.id(), token, "{\"capacity\":2,\"monthlyRent\":4000,\"securityDeposit\":4000}");
        assertThat(grown.status()).isEqualTo(200);
        assertThat(grown.body().get("beds")).hasSize(2);
        assertThat(api.put("/api/v1/rooms/" + roomId, token, "{\"monthlyRent\":1,\"securityDeposit\":1}").status())
                .isEqualTo(400); // ROOM spaces still need roomType + capacity
    }

    @Test
    void concurrentBedAddsKeepCapacityEqualToTheActiveBedCount() throws Exception {
        long hall = createSpace("\"roomNumber\":\"H2\",\"spaceKind\":\"HALL\",\"monthlyRent\":4000,\"securityDeposit\":4000").id();
        List<Callable<Response>> adds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            adds.add(() -> addBed(hall, "{}"));
        }
        List<Response> results = ConcurrencySupport.runConcurrently(adds);
        assertThat(results).allSatisfy(r -> assertThat(r.status()).isEqualTo(201));
        assertThat(results).extracting(r -> r.body().get("bedCode").asText()).doesNotHaveDuplicates();
        int active = fixtures.count("SELECT count(*) FROM beds WHERE room_id = ? AND deleted_at IS NULL", hall);
        assertThat(active).isEqualTo(10);
        assertThat(fixtures.count("SELECT capacity FROM rooms WHERE id = ?", hall)).isEqualTo(active);
    }

    @Test
    void reservationLocksInTheBedPriceForCheckIn() {
        long bed = layout.bedIds().get(1);
        assertThat(api.patch("/api/v1/beds/" + bed, token, "{\"monthlyRentOverride\":8000}").status()).isEqualTo(200);
        long tenant = fixtures.tenant(org, layout.propertyId());
        Response booking = api.post("/api/v1/bookings", token, "{\"tenantId\":" + tenant + ",\"bedId\":" + bed
                + ",\"expectedCheckInDate\":\"" + TODAY + "\"}");
        assertThat(booking.status()).isEqualTo(201);
        assertThat(booking.body().get("quotedMonthlyRent").decimalValue()).isEqualByComparingTo("8000");
        assertThat(booking.body().get("quotedSecurityDeposit").decimalValue()).isEqualByComparingTo("18000");
        assertThat(booking.body().get("bedCode").asText()).isEqualTo("B");
        assertThat(booking.body().get("roomNumber").asText()).isEqualTo("101");
        assertThat(booking.body().get("tenantName").asText()).startsWith("Tenant ");

        // a later price change does not affect the reservation already made; an explicit override still wins
        assertThat(api.patch("/api/v1/beds/" + bed, token, "{\"monthlyRentOverride\":8500}").status()).isEqualTo(200);
        Response checkIn = api.post("/api/v1/allocations/check-in", token, "{\"tenantId\":" + tenant + ",\"bookingId\":"
                + booking.id() + ",\"checkInDate\":\"" + TODAY + "\"}");
        assertThat(checkIn.status()).isEqualTo(201);
        assertThat(checkIn.body().at("/allocation/monthlyRent").decimalValue()).isEqualByComparingTo("8000");

        // clearing the override returns the bed to the room price
        Response cleared = api.patch("/api/v1/beds/" + layout.bedIds().get(0), token,
                "{\"monthlyRentOverride\":6000}");
        assertThat(cleared.body().get("monthlyRent").decimalValue()).isEqualByComparingTo("6000");
        cleared = api.patch("/api/v1/beds/" + layout.bedIds().get(0), token, "{\"clear\":[\"MONTHLY_RENT_OVERRIDE\"]}");
        assertThat(cleared.body().has("monthlyRentOverride")).isFalse();
        assertThat(cleared.body().get("monthlyRent").decimalValue()).isEqualByComparingTo("9000");
        assertThat(api.get("/api/v1/bookings?propertyId=" + layout.propertyId(), token).body().at("/content/0/tenantName")
                .asText()).startsWith("Tenant ");
    }
}
