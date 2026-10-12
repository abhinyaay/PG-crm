package com.pgms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pgms.integration.Api.Response;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

/**
 * The core guarantee of the platform: however many requests race for one bed, exactly one wins and the database
 * never holds two live reservations/occupancies for it.
 */
class BedAllocationConcurrencyIT extends AbstractIntegrationTest {

    private static final int CONTENDERS = 8;

    @Test
    void concurrentReservationsOfOneBedProduceExactlyOneWinner() throws Exception {
        Fixtures.Org org = fixtures.organization();
        Fixtures.PropertyLayout layout = fixtures.property(org, 1);
        long bed = layout.bedIds().get(0);
        fixtures.user(org, 2, "admin");
        String token = api.login(org.email("admin"));
        List<Callable<Response>> tasks = new ArrayList<>();
        for (int i = 0; i < CONTENDERS; i++) {
            long tenant = fixtures.tenant(org, layout.propertyId());
            tasks.add(() -> api.post("/api/v1/bookings", token, "{\"tenantId\":" + tenant + ",\"bedId\":" + bed
                    + ",\"expectedCheckInDate\":\"" + LocalDate.now() + "\"}"));
        }

        List<Response> responses = ConcurrencySupport.runConcurrently(tasks);

        assertThat(responses).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(responses).filteredOn(r -> r.status() == 409).hasSize(CONTENDERS - 1)
                .allSatisfy(r -> assertThat(r.body().get("code").asText()).isIn("BED_NOT_AVAILABLE", "BED_ALREADY_RESERVED",
                        "CONCURRENT_MODIFICATION"));
        assertThat(fixtures.count("SELECT count(*) FROM bookings WHERE bed_id = ? AND status = 'RESERVED'", bed)).isEqualTo(1);
        assertThat(fixtures.bedStatus(bed)).isEqualTo("RESERVED");
    }

    @Test
    void concurrentWalkInCheckInsCannotDoubleAllocate() throws Exception {
        Fixtures.Org org = fixtures.organization();
        Fixtures.PropertyLayout layout = fixtures.property(org, 1);
        long bed = layout.bedIds().get(1);
        fixtures.user(org, 2, "admin");
        String token = api.login(org.email("admin"));
        List<Callable<Response>> tasks = new ArrayList<>();
        for (int i = 0; i < CONTENDERS; i++) {
            long tenant = fixtures.tenant(org, layout.propertyId());
            tasks.add(() -> api.post("/api/v1/allocations/check-in", token, "{\"tenantId\":" + tenant + ",\"bedId\":" + bed
                    + ",\"checkInDate\":\"" + LocalDate.now() + "\"}"));
        }

        List<Response> responses = ConcurrencySupport.runConcurrently(tasks);

        assertThat(responses).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(responses).filteredOn(r -> r.status() == 409).hasSize(CONTENDERS - 1);
        assertThat(fixtures.count("SELECT count(*) FROM allocations WHERE bed_id = ? AND status = 'ACTIVE'", bed)).isEqualTo(1);
        assertThat(fixtures.count("SELECT count(*) FROM tenants WHERE property_id = ? AND status = 'ACTIVE'",
                layout.propertyId())).isEqualTo(1);
        // the losers' transactions rolled back completely: no orphan invoices
        assertThat(fixtures.count("SELECT count(*) FROM invoices WHERE property_id = ?", layout.propertyId())).isEqualTo(2);
    }

    @Test
    void oppositeTransfersDoNotDeadlock() throws Exception {
        Fixtures.Org org = fixtures.organization();
        Fixtures.PropertyLayout layout = fixtures.property(org, 2);
        fixtures.user(org, 2, "admin");
        String token = api.login(org.email("admin"));
        String today = LocalDate.now().toString();
        long tenantA = fixtures.tenant(org, layout.propertyId());
        long tenantB = fixtures.tenant(org, layout.propertyId());
        long allocA = api.post("/api/v1/allocations/check-in", token, "{\"tenantId\":" + tenantA + ",\"bedId\":"
                + layout.bedIds().get(0) + ",\"checkInDate\":\"" + today + "\"}").body().at("/allocation/id").asLong();
        long allocB = api.post("/api/v1/allocations/check-in", token, "{\"tenantId\":" + tenantB + ",\"bedId\":"
                + layout.bedIds().get(2) + ",\"checkInDate\":\"" + today + "\"}").body().at("/allocation/id").asLong();
        long freeBed = layout.bedIds().get(1);

        // A and B both try to move into the same free bed while holding their own beds: lock ordering by bed id
        // prevents a deadlock; one transfer wins, the other gets a clean 409
        List<Response> responses = ConcurrencySupport.runConcurrently(List.of(
                () -> api.post("/api/v1/allocations/" + allocA + "/transfer", token,
                        "{\"targetBedId\":" + freeBed + ",\"transferDate\":\"" + today + "\"}"),
                () -> api.post("/api/v1/allocations/" + allocB + "/transfer", token,
                        "{\"targetBedId\":" + freeBed + ",\"transferDate\":\"" + today + "\"}")));

        assertThat(responses).extracting(Response::status).containsExactlyInAnyOrder(200, 409);
        assertThat(fixtures.count("SELECT count(*) FROM allocations WHERE bed_id = ? AND status = 'ACTIVE'", freeBed)).isEqualTo(1);
    }
}
