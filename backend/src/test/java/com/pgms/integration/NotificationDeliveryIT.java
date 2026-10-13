package com.pgms.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.pgms.support.BusinessDates;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** End-to-end delivery: business event → outbox rows → dispatcher → real SMTP / HTTP providers. */
class NotificationDeliveryIT extends AbstractIntegrationTest {

    @Autowired
    private Outbox outbox;

    @Test
    void reservationReachesTheResidentByMultipartEmailAndDltSms() throws Exception {
        Fixtures.Org org = fixtures.organization();
        Fixtures.PropertyLayout layout = fixtures.property(org, 1);
        long manager = fixtures.user(org, 3, "manager");
        fixtures.assign(manager, layout.propertyId());
        String token = api.login(org.email("manager"));
        long tenant = fixtures.tenant(org, layout.propertyId());
        String email = fixtures.jdbc().queryForObject("SELECT email FROM tenants WHERE id = ?", String.class, tenant);
        String phone = fixtures.jdbc().queryForObject("SELECT phone FROM tenants WHERE id = ?", String.class, tenant);

        Api.Response booking = api.post("/api/v1/bookings", token, "{\"tenantId\":" + tenant + ",\"bedId\":"
                + layout.bedIds().get(0) + ",\"expectedCheckInDate\":\"" + BusinessDates.today().plusDays(3) + "\",\"holdHours\":24}");
        assertThat(booking.status()).isEqualTo(201);
        String reference = booking.body().get("bookingReference").asText();

        // outbox: no login -> no in-app row; email + SMS (tenants always have a phone)
        assertThat(outbox.rows("SELECT channel FROM notifications WHERE reference_type = 'BOOKING' AND reference_id = ? ORDER BY id",
                booking.id())).extracting(r -> r.get("channel")).containsExactly("EMAIL", "SMS");

        outbox.dispatchAll();

        List<MimeMessage> received = outbox.emailsTo(email);
        assertThat(received).hasSize(1);
        MimeMessage message = received.get(0);
        assertThat(message.getSubject()).isEqualTo("Bed reserved - " + reference);
        assertThat(((InternetAddress) message.getFrom()[0]).getAddress()).isEqualTo("no-reply@pgms.test");
        assertThat(((InternetAddress) message.getFrom()[0]).getPersonal()).isEqualTo("PGMS Test");
        assertThat(message.getContentType()).startsWith("multipart/");
        Map<String, String> parts = Outbox.textParts(message);
        assertThat(parts).containsKeys("text/plain", "text/html");
        assertThat(parts.get("text/plain")).contains("Your bed is reserved for check-in on");
        assertThat(parts.get("text/html")).contains("Test " + org.slug()).contains("Bed reserved - " + reference)
                .contains("<meta name=\"viewport\"");

        String mobile = phone.substring(1);
        List<SmsGatewayStub.Request> sms = outbox.sms().requestsTo(mobile);
        assertThat(sms).hasSize(1);
        assertThat(sms.get(0).path()).isEqualTo("/api/v5/flow");
        assertThat(sms.get(0).authKey()).isEqualTo("test-msg91-key");
        assertThat(sms.get(0).body().get("template_id").asText()).isEqualTo("1107160000000000001");
        assertThat(sms.get(0).recipient().get("ref").asText()).isEqualTo(reference);
        assertThat(sms.get(0).recipient().get("pg").asText()).isEqualTo("Test " + org.slug());
        assertThat(sms.get(0).body().has("text")).isFalse();

        assertThat(outbox.rows("SELECT status, attempts, sent_at FROM notifications WHERE reference_type = 'BOOKING' "
                + "AND reference_id = ?", booking.id())).allSatisfy(r -> {
                    assertThat(r.get("status")).isEqualTo("SENT");
                    assertThat(r.get("attempts")).isEqualTo(1);
                    assertThat(r.get("sent_at")).isNotNull();
                });
    }

    /** One failing row must neither block nor roll back the others in the same batch. */
    @Test
    void failingRowsAreIsolatedRetriedWithBackoffOrFailedPermanently() {
        Fixtures.Org org = fixtures.organization();
        outbox.sms().respondWith("919811100001", 200);
        outbox.sms().respondWith("919811100002", 503);
        outbox.sms().respondWith("919811100003", 400);
        long ok = pendingSms(org, "+919811100001");
        long transientFailure = pendingSms(org, "+919811100002");
        long rejected = pendingSms(org, "+919811100003");
        long noTemplate = pendingSmsOfType(org, "+919811100001", "ROOM_TRANSFER");
        Instant before = Instant.now();

        outbox.dispatchAll();

        assertThat(status(ok)).containsEntry("status", "SENT").containsEntry("attempts", 1);
        Map<String, Object> retry = status(transientFailure);
        assertThat(retry).containsEntry("status", "PENDING").containsEntry("attempts", 1);
        assertThat((String) retry.get("last_error")).contains("503");
        Instant next = ((Timestamp) retry.get("next_attempt_at")).toInstant();
        assertThat(next).isCloseTo(before.plus(1, ChronoUnit.MINUTES), within(30, ChronoUnit.SECONDS));
        Map<String, Object> failed = status(rejected);
        assertThat(failed).containsEntry("status", "FAILED").containsEntry("attempts", 1);
        assertThat((String) failed.get("last_error")).contains("400");
        assertThat(status(noTemplate)).containsEntry("status", "FAILED");
        assertThat((String) status(noTemplate).get("last_error")).contains("No SMS template");
    }

    private long pendingSms(Fixtures.Org org, String phone) {
        return pendingSmsOfType(org, phone, "BOOKING_RELEASED");
    }

    private long pendingSmsOfType(Fixtures.Org org, String phone, String type) {
        return fixtures.jdbc().queryForObject("""
                INSERT INTO notifications (organization_id, recipient_address, channel, type, title, body, status, attempts,
                                           next_attempt_at, template_params)
                VALUES (?, ?, 'SMS', ?, 'Released', 'x', 'PENDING', 0, now() - interval '1 second', ?::jsonb)
                RETURNING id""", Long.class, org.id(), phone, type,
                "{\"pg\":\"Test\",\"ref\":\"BK-1\",\"outcome\":\"expired\"}");
    }

    private Map<String, Object> status(long id) {
        return fixtures.jdbc().queryForMap("SELECT status, attempts, last_error, next_attempt_at FROM notifications WHERE id = ?", id);
    }
}
