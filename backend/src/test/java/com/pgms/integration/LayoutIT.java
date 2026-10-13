package com.pgms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.pgms.integration.Api.Response;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Floor layout editor: spaces with geometry, the bulk layout update, and the floor grid. */
class LayoutIT extends AbstractIntegrationTest {

    /** The API validates dates in the business time zone; the JVM running the tests may be in UTC. */
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Fixtures.Org org;
    private Fixtures.PropertyLayout layout;
    private String token;
    private String layoutUrl;

    @BeforeEach
    void setUp() {
        org = fixtures.organization();
        layout = fixtures.property(org, 2);
        fixtures.user(org, 2, "admin");
        token = api.login(org.email("admin"));
        layoutUrl = "/api/v1/properties/" + layout.propertyId() + "/floors/" + layout.floorId() + "/layout";
    }

    private JsonNode space(JsonNode layoutBody, long id) {
        for (JsonNode space : layoutBody.get("spaces")) {
            if (space.get("id").asLong() == id) {
                return space;
            }
        }
        throw new AssertionError("space " + id + " not in layout");
    }

    private long version(long roomId) {
        return space(api.get(layoutUrl, token).body(), roomId).get("version").asLong();
    }

    private static String placement(long id, long version, Integer x, Integer y, Integer w, Integer h) {
        return "{\"id\":" + id + ",\"version\":" + version + ",\"layoutX\":" + x + ",\"layoutY\":" + y + ",\"layoutW\":" + w
                + ",\"layoutH\":" + h + "}";
    }

    private Response put(String... placements) {
        return api.put(layoutUrl, token, "{\"spaces\":[" + String.join(",", placements) + "]}");
    }

    private Response createSpace(String json) {
        return api.post("/api/v1/rooms?propertyId=" + layout.propertyId(), token, "{\"floorId\":" + layout.floorId() + "," + json + "}");
    }

    @Test
    void spacesArePlacedMovedAndValidatedAgainstTheWholeFloor() {
        long r101 = layout.roomIds().get(0);
        long r102 = layout.roomIds().get(1);

        JsonNode initial = api.get(layoutUrl, token).body();
        assertThat(initial.get("gridCols").asInt()).isEqualTo(24);
        assertThat(initial.get("gridRows").asInt()).isEqualTo(16);
        assertThat(initial.get("buildingCode").asText()).isEqualTo("A");
        assertThat(initial.get("spaces")).hasSize(2);
        JsonNode unplaced = space(initial, r101);
        assertThat(unplaced.get("placed").asBoolean()).isFalse();
        assertThat(unplaced.has("layoutX")).isFalse();
        assertThat(unplaced.get("spaceKind").asText()).isEqualTo("ROOM");
        assertThat(unplaced.get("beds")).hasSize(2);
        assertThat(unplaced.at("/beds/0/status").asText()).isEqualTo("AVAILABLE");
        assertThat(unplaced.at("/beds/0/monthlyRent").decimalValue()).isEqualByComparingTo("9000");

        Response placed = put(
                "{\"id\":" + r101 + ",\"version\":" + unplaced.get("version").asLong()
                        + ",\"layoutX\":0,\"layoutY\":0,\"layoutW\":4,\"layoutH\":3,\"label\":\"Sunny\",\"color\":\"#AABBCC\"}",
                placement(r102, space(initial, r102).get("version").asLong(), 4, 0, 4, 3));
        assertThat(placed.status()).isEqualTo(200);
        JsonNode s101 = space(placed.body(), r101);
        assertThat(s101.get("placed").asBoolean()).isTrue();
        assertThat(s101.get("layoutW").asInt()).isEqualTo(4);
        assertThat(s101.get("label").asText()).isEqualTo("Sunny");
        assertThat(s101.get("color").asText()).isEqualTo("#AABBCC");
        assertThat(s101.get("version").asLong()).isGreaterThan(unplaced.get("version").asLong());
        assertThat(s101.get("version").asLong()).isEqualTo(version(r101)); // the response carries the committed version

        // bed-less and open-plan spaces through the existing create endpoint
        Response kitchen = createSpace("\"roomNumber\":\"K1\",\"spaceKind\":\"KITCHEN\",\"layoutX\":0,\"layoutY\":3,"
                + "\"layoutW\":8,\"layoutH\":2,\"label\":\"Kitchen\"");
        assertThat(kitchen.status()).isEqualTo(201);
        assertThat(kitchen.body().get("capacity").asInt()).isZero();
        assertThat(kitchen.body().get("beds")).isEmpty();
        assertThat(kitchen.body().has("roomType")).isFalse();
        Response hall = createSpace("\"roomNumber\":\"H1\",\"spaceKind\":\"HALL\",\"capacity\":3,\"monthlyRent\":5000,"
                + "\"securityDeposit\":5000,\"layoutX\":8,\"layoutY\":0,\"layoutW\":6,\"layoutH\":6");
        assertThat(hall.status()).isEqualTo(201);
        assertThat(hall.body().get("roomType").asText()).isEqualTo("DORMITORY");
        assertThat(hall.body().get("beds")).hasSize(3);
        assertThat(fixtures.jdbc().queryForObject("SELECT space_kind FROM analytics.rooms WHERE id = ?", String.class,
                kitchen.id())).isEqualTo("KITCHEN");

        assertThat(createSpace("\"roomNumber\":\"S1\",\"spaceKind\":\"STORE\",\"layoutX\":1,\"layoutY\":1,"
                + "\"layoutW\":1,\"layoutH\":1").body().get("code").asText()).isEqualTo("LAYOUT_OVERLAP");
        Response bedlessWithBeds = createSpace("\"roomNumber\":\"S2\",\"spaceKind\":\"STORE\",\"capacity\":2");
        assertThat(bedlessWithBeds.status()).isEqualTo(400);
        assertThat(createSpace("\"roomNumber\":\"S3\",\"spaceKind\":\"HALL\",\"roomType\":\"SINGLE\",\"monthlyRent\":1,"
                + "\"securityDeposit\":1").body().get("code").asText()).isEqualTo("INVALID_ROOM_TYPE");
        assertThat(createSpace("\"roomNumber\":\"S4\",\"roomType\":\"DOUBLE\",\"capacity\":2,\"monthlyRent\":1").status())
                .isEqualTo(400); // ROOM without deposit: still a validation error as before

        JsonNode full = api.get(layoutUrl, token).body();
        assertThat(full.get("spaces")).hasSize(4);

        // overlap with 101 after the move: rejected, nothing changes
        Response overlap = put(placement(r102, version(r102), 2, 1, 4, 3));
        assertThat(overlap.status()).isEqualTo(422);
        assertThat(overlap.body().get("code").asText()).isEqualTo("LAYOUT_OVERLAP");
        assertThat(space(api.get(layoutUrl, token).body(), r102).get("layoutX").asInt()).isEqualTo(4);

        Response outside = put(placement(r101, version(r101), 22, 0, 4, 3));
        assertThat(outside.status()).isEqualTo(422);
        assertThat(outside.body().get("code").asText()).isEqualTo("LAYOUT_OUT_OF_BOUNDS");

        Response stale = put(placement(r101, version(r101) - 1, 0, 0, 3, 3));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.body().get("code").asText()).isEqualTo("LAYOUT_STALE");

        Response partial = put("{\"id\":" + r101 + ",\"version\":" + version(r101) + ",\"layoutX\":1}");
        assertThat(partial.status()).isEqualTo(400);
        assertThat(partial.body().get("code").asText()).isEqualTo("INVALID_GEOMETRY");

        assertThat(put(placement(r101, version(r101), 0, 0, 4, 3), placement(r101, version(r101), 0, 0, 4, 3)).status())
                .isEqualTo(400);
        assertThat(put(placement(hall.id() + 100_000, 0, 0, 0, 1, 1)).status()).isEqualTo(404);

        // swapping two spaces is only valid as a whole: validated against the final state, in one transaction
        Response swapped = put(placement(r101, version(r101), 4, 0, 4, 3), placement(r102, version(r102), 0, 0, 4, 3));
        assertThat(swapped.status()).isEqualTo(200);
        assertThat(space(swapped.body(), r101).get("layoutX").asInt()).isEqualTo(4);
        assertThat(space(swapped.body(), r101).get("label").asText()).isEqualTo("Sunny"); // label untouched when omitted

        Response removed = put("{\"id\":" + r102 + ",\"version\":" + version(r102) + ",\"label\":\"\"}");
        assertThat(removed.status()).isEqualTo(200);
        assertThat(space(removed.body(), r102).get("placed").asBoolean()).isFalse();
    }

    @Test
    void floorGridCanOnlyShrinkAroundPlacedSpaces() {
        long r101 = layout.roomIds().get(0);
        assertThat(put(placement(r101, version(r101), 10, 10, 4, 4)).status()).isEqualTo(200);

        Response tooSmall = api.patch("/api/v1/floors/" + layout.floorId(), token, "{\"gridCols\":12}");
        assertThat(tooSmall.status()).isEqualTo(422);
        assertThat(tooSmall.body().get("code").asText()).isEqualTo("LAYOUT_OUT_OF_BOUNDS");
        assertThat(api.patch("/api/v1/floors/" + layout.floorId(), token, "{\"gridCols\":0}").status()).isEqualTo(400);

        Response resized = api.patch("/api/v1/floors/" + layout.floorId(), token,
                "{\"gridCols\":14,\"gridRows\":30,\"label\":\"Ground floor\"}");
        assertThat(resized.status()).isEqualTo(200);
        assertThat(resized.body().get("gridCols").asInt()).isEqualTo(14);
        assertThat(resized.body().get("label").asText()).isEqualTo("Ground floor");
        JsonNode floor = api.get(layoutUrl, token).body();
        assertThat(floor.get("gridRows").asInt()).isEqualTo(30);
        assertThat(floor.get("label").asText()).isEqualTo("Ground floor");

        // floors created through the API can choose their grid
        long buildingId = fixtures.jdbc().queryForObject("SELECT building_id FROM floors WHERE id = ?", Long.class, layout.floorId());
        Response created = api.post("/api/v1/properties/" + layout.propertyId() + "/buildings/" + buildingId + "/floors", token,
                "{\"floorNumber\":2,\"gridCols\":40,\"gridRows\":20}");
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("gridCols").asInt()).isEqualTo(40);
    }

    @Test
    void staffCanReadButNotEditAndOtherOrganizationsSeeNothing() {
        long staff = fixtures.user(org, 4, "staff");
        fixtures.assign(staff, layout.propertyId());
        String staffToken = api.login(org.email("staff"));
        assertThat(api.get(layoutUrl, staffToken).status()).isEqualTo(200);
        long r101 = layout.roomIds().get(0);
        assertThat(api.put(layoutUrl, staffToken, "{\"spaces\":[" + placement(r101, version(r101), 0, 0, 1, 1) + "]}").status())
                .isEqualTo(403);
        assertThat(api.patch("/api/v1/floors/" + layout.floorId(), staffToken, "{\"gridCols\":30}").status()).isEqualTo(403);

        Fixtures.Org other = fixtures.organization();
        fixtures.user(other, 2, "admin");
        String foreign = api.login(other.email("admin"));
        assertThat(api.get(layoutUrl, foreign).status()).isEqualTo(404);
        assertThat(api.patch("/api/v1/floors/" + layout.floorId(), foreign, "{\"gridCols\":30}").status()).isEqualTo(404);

        // a floor of another property cannot be addressed through this property
        Fixtures.PropertyLayout second = fixtures.property(org, 1);
        assertThat(api.get("/api/v1/properties/" + layout.propertyId() + "/floors/" + second.floorId() + "/layout", token)
                .status()).isEqualTo(404);
    }

    /** The layout read is a fixed number of statements however many spaces and beds the floor has. */
    @Test
    void layoutReadIssuesTheSameNumberOfQueriesForSmallAndLargeFloors() {
        Fixtures.PropertyLayout small = fixtures.property(org, 1);
        Fixtures.PropertyLayout large = fixtures.property(org, 8);
        String smallUrl = "/api/v1/properties/" + small.propertyId() + "/floors/" + small.floorId() + "/layout";
        String largeUrl = "/api/v1/properties/" + large.propertyId() + "/floors/" + large.floorId() + "/layout";
        api.get(smallUrl, token); // warm-up (caches, lazy initialisation)

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            assertThat(api.get(smallUrl, token).body().get("spaces")).hasSize(1);
            long smallFloor = statistics.getPrepareStatementCount();
            statistics.clear();
            assertThat(api.get(largeUrl, token).body().get("spaces")).hasSize(8);
            long largeFloor = statistics.getPrepareStatementCount();
            assertThat(largeFloor).isEqualTo(smallFloor).isLessThanOrEqualTo(5);
        } finally {
            statistics.setStatisticsEnabled(wasEnabled);
        }
    }

    @Test
    void namesAreResolvedForAPageOfAllocationsWithoutExtraQueriesPerRow() {
        Fixtures.PropertyLayout big = fixtures.property(org, 3);
        for (int i = 0; i < 5; i++) {
            long tenant = fixtures.tenant(org, big.propertyId());
            assertThat(api.post("/api/v1/allocations/check-in", token, "{\"tenantId\":" + tenant + ",\"bedId\":"
                    + big.bedIds().get(i) + ",\"checkInDate\":\"" + TODAY + "\"}").status()).isEqualTo(201);
        }
        String url = "/api/v1/allocations?propertyId=" + big.propertyId();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            JsonNode one = api.get(url + "&size=1", token).body();
            long onePerPage = statistics.getPrepareStatementCount();
            statistics.clear();
            JsonNode five = api.get(url + "&size=5", token).body();
            long fivePerPage = statistics.getPrepareStatementCount();
            assertThat(fivePerPage).isEqualTo(onePerPage);
            assertThat(one.get("content")).hasSize(1);
            assertThat(five.get("content")).hasSize(5).allSatisfy(a -> {
                assertThat(a.get("tenantName").asText()).startsWith("Tenant ");
                assertThat(a.get("roomNumber").asText()).startsWith("10");
                assertThat(a.get("bedCode").asText()).isIn("A", "B");
            });
        } finally {
            statistics.setStatisticsEnabled(wasEnabled);
        }
    }

    @Test
    void concurrentEditorsCannotBothCommitOverlappingSpaces() throws Exception {
        long r101 = layout.roomIds().get(0);
        long r102 = layout.roomIds().get(1);
        long v101 = version(r101);
        long v102 = version(r102);
        // each request alone is valid; together they would overlap at (2..4, 0..3)
        List<Callable<Response>> editors = new ArrayList<>();
        editors.add(() -> put(placement(r101, v101, 0, 0, 4, 3)));
        editors.add(() -> put(placement(r102, v102, 2, 0, 4, 3)));
        List<Response> results = ConcurrencySupport.runConcurrently(editors);
        assertThat(results).filteredOn(r -> r.status() == 200).hasSize(1);
        assertThat(results).filteredOn(r -> r.status() == 422).hasSize(1)
                .allSatisfy(r -> assertThat(r.body().get("code").asText()).isEqualTo("LAYOUT_OVERLAP"));
        Set<Boolean> placed = new HashSet<>();
        api.get(layoutUrl, token).body().get("spaces").forEach(s -> placed.add(s.get("placed").asBoolean()));
        assertThat(placed).containsExactlyInAnyOrder(true, false);
    }
}
