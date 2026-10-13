package com.pgms.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.pgms.access.AccessCredentialService;
import com.pgms.access.AccessDeviceMonitorJob;
import com.pgms.booking.BookingEvents;
import com.pgms.support.BusinessDates;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Biometric entry/exit module end to end: ZKTeco ADMS push protocol, bridge webhook and door-controller verify
 * (device key), daily entry limits, exit modes, manual overrides, presence, the offline job and RBAC / scoping.
 */
class BiometricAccessIT extends AbstractIntegrationTest {

    private static final DateTimeFormatter DEVICE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AccessDeviceMonitorJob monitorJob;

    @Autowired
    private AccessCredentialService credentialService;

    @Autowired
    private TransactionTemplate transactions;

    // ---- fixtures ----

    /** An organization with one property, an owner (PROPERTY_ADMIN) and an assigned MANAGER. */
    record Site(Fixtures.Org org, long propertyId, String adminToken, long managerId, String managerToken) {
    }

    record Device(long id, String serial, String apiKey) {
    }

    record Resident(long tenantId, long userId, String token, String pin) {
    }

    private Site site() {
        Fixtures.Org org = fixtures.organization();
        Fixtures.PropertyLayout layout = fixtures.property(org, 1);
        fixtures.user(org, 2, "owner");
        long manager = fixtures.user(org, 3, "manager");
        fixtures.assign(manager, layout.propertyId());
        return new Site(org, layout.propertyId(), api.login(org.email("owner")), manager, api.login(org.email("manager")));
    }

    private void settings(Site site, boolean enabled, String exitMode, Integer limit) {
        settings(site, enabled, exitMode, limit, false);
    }

    private void settings(Site site, boolean enabled, String exitMode, Integer limit, boolean trackStaff) {
        Api.Response r = api.put("/api/v1/properties/" + site.propertyId() + "/access-settings", site.adminToken(),
                "{\"enabled\":" + enabled + ",\"exitMode\":\"" + exitMode + "\",\"maxEntriesPerDay\":" + limit
                        + ",\"trackStaff\":" + trackStaff + "}");
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    }

    private Device device(Site site, String direction, String vendor) {
        String serial = "SN" + UUID.randomUUID().toString().substring(0, 10).toUpperCase();
        Api.Response r = api.post("/api/v1/access/devices", site.managerToken(), "{\"propertyId\":" + site.propertyId()
                + ",\"name\":\"Main gate\",\"vendor\":\"" + vendor + "\",\"serialNumber\":\"" + serial.toLowerCase()
                + "\",\"direction\":\"" + direction + "\"}");
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        assertThat(r.body().at("/device/serialNumber").asText()).isEqualTo(serial);
        assertThat(r.body().at("/setup/admsPath").asText()).isEqualTo("/iclock/cdata");
        return new Device(r.body().at("/device/id").asLong(), serial, r.body().get("apiKey").asText());
    }

    private Device device(Site site, String direction) {
        return device(site, direction, "ZKTECO_ADMS");
    }

    private Resident resident(Site site, String pin) {
        long tenantId = fixtures.tenant(site.org(), site.propertyId());
        fixtures.jdbc().update("UPDATE tenants SET status = 'ACTIVE' WHERE id = ?", tenantId);
        String local = "res" + tenantId;
        long userId = fixtures.user(site.org(), 5, local);
        fixtures.linkTenantUser(tenantId, userId);
        if (pin != null) {
            Api.Response r = api.post("/api/v1/access/credentials", site.managerToken(), "{\"propertyId\":" + site.propertyId()
                    + ",\"tenantId\":" + tenantId + ",\"deviceUserId\":\"" + pin + "\"}");
            assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        }
        return new Resident(tenantId, userId, api.login(site.org().email(local)), pin);
    }

    /** A recent IST time on today's business date, so punches count for "today" and trigger alerts. */
    private static LocalDateTime base() {
        LocalDateTime now = LocalDateTime.now(BusinessDates.ZONE).withNano(0);
        LocalDateTime candidate = now.minusMinutes(5);
        LocalDateTime start = now.toLocalDate().atStartOfDay();
        return candidate.isBefore(start) ? start : candidate;
    }

    private static String line(String pin, LocalDateTime time, int status, int verify) {
        return pin + "\t" + DEVICE_TIME.format(time) + "\t" + status + "\t" + verify + "\t0\t0\t0";
    }

    private MvcResult adms(MockHttpServletRequestBuilder request) {
        try {
            return mvc.perform(request).andReturn();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String attlog(String serial, String body, int expectedStatus) {
        MvcResult result = adms(post("/iclock/cdata?SN=" + serial + "&table=ATTLOG&Stamp=9999")
                .contentType(MediaType.TEXT_PLAIN).content(body.getBytes(StandardCharsets.UTF_8)));
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        try {
            return result.getResponse().getContentAsString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String text(MvcResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int events(long tenantId, String result) {
        return fixtures.count("SELECT count(*) FROM access_events WHERE tenant_id = ? AND result = ?", tenantId, result);
    }

    private int notifications(long userId, String type, String channel) {
        return fixtures.count("SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND type = ? AND channel = ?",
                userId, type, channel);
    }

    // ---- ADMS push protocol ----

    @Test
    void admsHandshakeHeartbeatAndUploadAndRejectsUnknownOrDisabledSerials() {
        Site site = site();
        settings(site, true, "DEVICE_OR_BUTTON", null);
        Device device = device(site, "BOTH");
        Resident resident = resident(site, "101");

        MvcResult handshake = adms(get("/iclock/cdata?SN=" + device.serial() + "&options=all&pushver=2.4.1&language=69"));
        assertThat(handshake.getResponse().getStatus()).isEqualTo(200);
        assertThat(text(handshake)).startsWith("GET OPTION FROM: " + device.serial() + "\n")
                .contains("ATTLOGStamp=None", "TransTimes=", "TransInterval=", "Realtime=1", "Encrypt=0", "TimeZone=330");
        assertThat(handshake.getResponse().getContentType()).startsWith("text/plain");
        assertThat(fixtures.count("SELECT count(*) FROM access_devices WHERE id = ? AND last_seen_at IS NOT NULL", device.id()))
                .isEqualTo(1);

        // serial numbers are matched case-insensitively; ".aspx" variants are used by some firmware
        MvcResult heartbeat = adms(get("/iclock/getrequest.aspx?SN=" + device.serial().toLowerCase() + "&INFO=Ver,1,2,3"));
        assertThat(heartbeat.getResponse().getStatus()).isEqualTo(200);
        assertThat(text(heartbeat)).isEqualTo("OK");

        LocalDateTime t = base();
        String body = line("101", t, 0, 1) + "\n" + "this line is garbage\n" + line("101", t.plusSeconds(30), 1, 15) + "\n";
        assertThat(attlog(device.serial(), body, 200)).isEqualTo("OK: 2");
        assertThat(events(resident.tenantId(), "ALLOWED")).isEqualTo(2);
        assertThat(fixtures.count("SELECT count(*) FROM access_events WHERE tenant_id = ? AND direction = 'EXIT' AND method = 'FACE'",
                resident.tenantId())).isEqualTo(1);
        // the device's stamp is echoed in the next handshake
        assertThat(text(adms(get("/iclock/cdata?SN=" + device.serial() + "&options=all")))).contains("ATTLOGStamp=9999");

        // other tables and command results are acknowledged
        MvcResult operlog = adms(post("/iclock/cdata?SN=" + device.serial() + "&table=OPERLOG").content("OPLOG 4\t0\t..."));
        assertThat(text(operlog)).isEqualTo("OK");
        assertThat(text(adms(post("/iclock/devicecmd?SN=" + device.serial()).content("ID=1&Return=0&CMD=INFO")))).isEqualTo("OK");

        // unknown serial: rejected on every endpoint, nothing stored
        String unknown = "NOPE" + UUID.randomUUID().toString().substring(0, 6);
        MvcResult rejected = adms(get("/iclock/cdata?SN=" + unknown + "&options=all"));
        assertThat(rejected.getResponse().getStatus()).isEqualTo(401);
        assertThat(text(rejected)).isEqualTo("ERROR: UNKNOWN DEVICE");
        assertThat(adms(get("/iclock/getrequest?SN=" + unknown)).getResponse().getStatus()).isEqualTo(401);
        assertThat(attlog(unknown, line("101", t.plusSeconds(60), 0, 1), 401)).isEqualTo("ERROR: UNKNOWN DEVICE");
        assertThat(adms(get("/iclock/cdata")).getResponse().getStatus()).isEqualTo(401);

        // disabled device: rejected like an unknown one
        assertThat(api.patch("/api/v1/access/devices/" + device.id(), site.managerToken(), "{\"status\":\"DISABLED\"}").status())
                .isEqualTo(200);
        assertThat(adms(get("/iclock/cdata?SN=" + device.serial() + "&options=all")).getResponse().getStatus()).isEqualTo(401);
        assertThat(attlog(device.serial(), line("101", t.plusSeconds(90), 0, 1), 401)).isEqualTo("ERROR: UNKNOWN DEVICE");
        assertThat(events(resident.tenantId(), "ALLOWED")).isEqualTo(2);
    }

    @Test
    void reSentLogsAreIdempotentAndOutOfOrderPunchesKeepTheLatestPresence() {
        Site site = site();
        settings(site, true, "DEVICE_OR_BUTTON", null);
        Device device = device(site, "BOTH");
        Resident resident = resident(site, "7");
        LocalDateTime t = base();
        String body = line("7", t, 0, 1) + "\n" + line("7", t.plusSeconds(20), 1, 1);

        assertThat(attlog(device.serial(), body, 200)).isEqualTo("OK: 2");
        assertThat(attlog(device.serial(), body, 200)).isEqualTo("OK: 2");
        // a duplicate line inside one upload is stored once too
        assertThat(attlog(device.serial(), line("7", t.plusSeconds(40), 0, 1) + "\n" + line("7", t.plusSeconds(40), 0, 1), 200))
                .isEqualTo("OK: 2");
        assertThat(fixtures.count("SELECT count(*) FROM access_events WHERE device_id = ?", device.id())).isEqualTo(3);

        // an older punch arriving late (device was offline) does not override the newer state: still IN
        assertThat(attlog(device.serial(), line("7", t.minusSeconds(60), 1, 1), 200)).isEqualTo("OK: 1");
        JsonNode me = api.get("/api/v1/access/me", resident.token()).body();
        assertThat(me.get("presence").asText()).isEqualTo("IN");
        assertThat(me.get("entriesToday").asInt()).isEqualTo(2);
    }

    // ---- daily entry limit ----

    @Test
    void entriesBeyondTheDailyLimitAreDeniedAndAlertManagersAndTheResident() {
        Site site = site();
        settings(site, true, "DEVICE_OR_BUTTON", 2);
        Device device = device(site, "ENTRY");
        Resident resident = resident(site, "55");
        LocalDateTime t = base();

        attlog(device.serial(), line("55", t, 0, 1) + "\n" + line("55", t.plusSeconds(1), 0, 1) + "\n"
                + line("55", t.plusSeconds(2), 0, 1), 200);

        assertThat(events(resident.tenantId(), "ALLOWED")).isEqualTo(2);
        assertThat(events(resident.tenantId(), "DENIED_LIMIT_EXCEEDED")).isEqualTo(1);
        assertThat(fixtures.jdbc().queryForObject("SELECT reason FROM access_events WHERE tenant_id = ? AND result = "
                + "'DENIED_LIMIT_EXCEEDED'", String.class, resident.tenantId())).contains("2 of 2");
        assertThat(notifications(site.managerId(), "ACCESS_LIMIT_EXCEEDED", "IN_APP")).isEqualTo(1);
        assertThat(fixtures.count("SELECT count(*) FROM notifications WHERE recipient_address = ? AND type = 'ACCESS_LIMIT_EXCEEDED' "
                + "AND channel = 'EMAIL'", site.org().email("manager"))).isEqualTo(1);
        assertThat(notifications(resident.userId(), "ACCESS_LIMIT_EXCEEDED", "IN_APP")).isEqualTo(1);
        assertThat(fixtures.count("SELECT count(*) FROM notifications WHERE type = 'ACCESS_LIMIT_EXCEEDED' AND channel = 'EMAIL' "
                + "AND organization_id = ?", site.org().id())).isEqualTo(1);

        JsonNode me = api.get("/api/v1/access/me", resident.token()).body();
        assertThat(me.get("entriesToday").asInt()).isEqualTo(2);
        assertThat(me.get("limit").asInt()).isEqualTo(2);
        assertThat(me.get("remainingEntries").asInt()).isZero();

        // the gate log filter shows the denial
        JsonNode denied = api.get("/api/v1/access/events?propertyId=" + site.propertyId() + "&result=DENIED_LIMIT_EXCEEDED"
                + "&from=" + BusinessDates.today() + "&to=" + BusinessDates.today(), site.managerToken()).body();
        assertThat(denied.get("totalElements").asInt()).isEqualTo(1);
        assertThat(denied.at("/content/0/personName").asText()).startsWith("Tenant");
        assertThat(denied.at("/content/0/deviceName").asText()).isEqualTo("Main gate");
    }

    @Test
    void perResidentOverrideBeatsThePropertyDefault() {
        Site site = site();
        settings(site, true, "DEVICE_OR_BUTTON", 1);
        Device device = device(site, "ENTRY");
        Resident vip = resident(site, "1");
        Resident strict = resident(site, "2");
        String path = "/api/v1/tenants/" + vip.tenantId() + "/access-limit";

        JsonNode unlimited = api.put(path, site.managerToken(), "{\"unlimited\":true}").body();
        assertThat(unlimited.get("override").asBoolean()).isTrue();
        assertThat(unlimited.get("unlimited").asBoolean()).isTrue();
        assertThat(unlimited.has("effectiveLimit")).isFalse();
        assertThat(unlimited.get("propertyDefault").asInt()).isEqualTo(1);
        assertThat(api.put("/api/v1/tenants/" + strict.tenantId() + "/access-limit", site.managerToken(),
                "{\"maxEntriesPerDay\":0}").status()).isEqualTo(400);

        LocalDateTime t = base();
        attlog(device.serial(), line("1", t, 0, 1) + "\n" + line("1", t.plusSeconds(1), 0, 1) + "\n"
                + line("1", t.plusSeconds(2), 0, 1) + "\n" + line("2", t, 0, 1) + "\n" + line("2", t.plusSeconds(1), 0, 1), 200);
        assertThat(events(vip.tenantId(), "ALLOWED")).isEqualTo(3);
        assertThat(events(strict.tenantId(), "ALLOWED")).isEqualTo(1);
        assertThat(events(strict.tenantId(), "DENIED_LIMIT_EXCEEDED")).isEqualTo(1);

        // personal limit 4 -> fourth entry allowed, fifth denied
        assertThat(api.put(path, site.managerToken(), "{\"maxEntriesPerDay\":4}").body().get("effectiveLimit").asInt()).isEqualTo(4);
        attlog(device.serial(), line("1", t.plusSeconds(3), 0, 1) + "\n" + line("1", t.plusSeconds(4), 0, 1), 200);
        assertThat(events(vip.tenantId(), "ALLOWED")).isEqualTo(4);
        assertThat(events(vip.tenantId(), "DENIED_LIMIT_EXCEEDED")).isEqualTo(1);

        // null -> back to the property default
        JsonNode reset = api.put(path, site.managerToken(), "{\"maxEntriesPerDay\":null}").body();
        assertThat(reset.get("override").asBoolean()).isFalse();
        assertThat(reset.get("effectiveLimit").asInt()).isEqualTo(1);
        assertThat(api.get(path, site.managerToken()).body().get("effectiveLimit").asInt()).isEqualTo(1);
        assertThat(fixtures.count("SELECT count(*) FROM audit_logs WHERE action = 'ACCESS_MEMBER_LIMIT_UPDATED' AND entity_id = ?",
                vip.tenantId())).isEqualTo(3);
    }

    // ---- exit modes ----

    @Test
    void exitButtonAndDeviceExitsFollowTheExitMode() {
        Site site = site();
        Device device = device(site, "BOTH");
        Resident resident = resident(site, "9");
        long staff = fixtures.user(site.org(), 4, "guard");
        fixtures.assign(staff, site.propertyId());
        String staffToken = api.login(site.org().email("guard"));
        LocalDateTime t = base();

        // DEVICE: no button
        settings(site, true, "DEVICE", null);
        attlog(device.serial(), line("9", t, 0, 1), 200);
        assertThat(api.get("/api/v1/access/me", resident.token()).body().get("exitButtonAvailable").asBoolean()).isFalse();
        Api.Response forbidden = api.post("/api/v1/access/exit", resident.token(), null);
        assertThat(forbidden.status()).isEqualTo(422);
        assertThat(forbidden.body().get("code").asText()).isEqualTo("EXIT_BUTTON_NOT_ALLOWED");
        attlog(device.serial(), line("9", t.plusSeconds(10), 1, 1), 200);
        assertThat(api.get("/api/v1/access/me", resident.token()).body().get("presence").asText()).isEqualTo("OUT");

        // BUTTON: device exits are stored but ignored, the button records the exit
        settings(site, true, "BUTTON", null);
        attlog(device.serial(), line("9", t.plusSeconds(20), 0, 1) + "\n" + line("9", t.plusSeconds(30), 1, 1), 200);
        assertThat(events(resident.tenantId(), "IGNORED_EXIT_MODE")).isEqualTo(1);
        JsonNode me = api.get("/api/v1/access/me", resident.token()).body();
        assertThat(me.get("presence").asText()).isEqualTo("IN");
        assertThat(me.get("exitButtonAvailable").asBoolean()).isTrue();
        Api.Response exit = api.post("/api/v1/access/exit", resident.token(), null);
        assertThat(exit.status()).as(exit.body().toString()).isEqualTo(201);
        assertThat(exit.body().get("method").asText()).isEqualTo("BUTTON");
        assertThat(exit.body().get("result").asText()).isEqualTo("ALLOWED");
        assertThat(api.get("/api/v1/access/me", resident.token()).body().get("presence").asText()).isEqualTo("OUT");

        // already OUT: stored, presence unchanged
        Api.Response again = api.post("/api/v1/access/exit", resident.token(), "{}");
        assertThat(again.status()).isEqualTo(201);
        assertThat(again.body().get("reason").asText()).isEqualTo("Already out");

        // DEVICE_OR_BUTTON: staff press the button on the resident's behalf (audited), devices count too
        settings(site, true, "DEVICE_OR_BUTTON", null);
        attlog(device.serial(), line("9", t.plusSeconds(40), 0, 1), 200);
        assertThat(api.post("/api/v1/access/exit", staffToken, "{}").body().get("code").asText()).isEqualTo("TENANT_ID_REQUIRED");
        Api.Response onBehalf = api.post("/api/v1/access/exit", staffToken, "{\"tenantId\":" + resident.tenantId() + "}");
        assertThat(onBehalf.status()).isEqualTo(201);
        assertThat(onBehalf.body().get("recordedBy").asLong()).isEqualTo(staff);
        assertThat(fixtures.count("SELECT count(*) FROM audit_logs WHERE action = 'ACCESS_EXIT_RECORDED' AND entity_id = ?",
                resident.tenantId())).isEqualTo(1);
        attlog(device.serial(), line("9", t.plusSeconds(50), 0, 1) + "\n" + line("9", t.plusSeconds(60), 1, 1), 200);
        assertThat(api.get("/api/v1/access/me", resident.token()).body().get("presence").asText()).isEqualTo("OUT");
        assertThat(events(resident.tenantId(), "IGNORED_EXIT_MODE")).isEqualTo(1);
    }

    // ---- manual override ----

    @Test
    void managerManualEntryIsAuditedAndBypassesTheLimit() {
        Site site = site();
        settings(site, true, "DEVICE_OR_BUTTON", 1);
        Device device = device(site, "ENTRY");
        Resident resident = resident(site, "3");
        attlog(device.serial(), line("3", base(), 0, 1), 200);
        long staff = fixtures.user(site.org(), 4, "staff");
        fixtures.assign(staff, site.propertyId());
        String body = "{\"tenantId\":" + resident.tenantId() + ",\"reason\":\"Fingerprint reader broken\"}";

        assertThat(api.post("/api/v1/access/manual-entry", api.login(site.org().email("staff")), body).status()).isEqualTo(403);
        assertThat(api.post("/api/v1/access/manual-entry", resident.token(), body).status()).isEqualTo(403);
        assertThat(api.post("/api/v1/access/manual-entry", site.managerToken(), "{\"tenantId\":" + resident.tenantId() + "}")
                .status()).isEqualTo(400);

        Api.Response r = api.post("/api/v1/access/manual-entry", site.managerToken(), body);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        assertThat(r.body().get("method").asText()).isEqualTo("MANUAL_OVERRIDE");
        assertThat(r.body().get("result").asText()).isEqualTo("ALLOWED");
        assertThat(r.body().get("recordedBy").asLong()).isEqualTo(site.managerId());
        assertThat(r.body().get("reason").asText()).contains("Fingerprint reader broken", "Over the daily entry limit");
        JsonNode audit = api.get("/api/v1/audit-logs?entityType=TENANT&entityId=" + resident.tenantId(), site.adminToken()).body();
        assertThat(fixtures.count("SELECT count(*) FROM audit_logs WHERE action = 'ACCESS_MANUAL_ENTRY' AND entity_id = ? "
                + "AND details->>'reason' = 'Fingerprint reader broken' AND details->>'overDailyLimit' = 'true'",
                resident.tenantId())).as(audit.toString()).isEqualTo(1);

        // the owner may also override; a resident who moved out may not be let in
        assertThat(api.post("/api/v1/access/manual-entry", site.adminToken(), body).status()).isEqualTo(201);
        fixtures.jdbc().update("UPDATE tenants SET status = 'MOVED_OUT' WHERE id = ?", resident.tenantId());
        assertThat(api.post("/api/v1/access/manual-entry", site.managerToken(), body).body().get("code").asText())
                .isEqualTo("TENANT_NOT_RESIDENT");
    }

    // ---- bridge webhook / door controller ----

    @Test
    void bridgeWebhookAuthenticatesWithTheDeviceKeyAndKeysCanBeRotated() {
        Site site = site();
        settings(site, true, "DEVICE_OR_BUTTON", null);
        Device device = device(site, "BOTH", "GENERIC_WEBHOOK");
        Resident resident = resident(site, "42");
        LocalDateTime t = base();
        String events = "{\"events\":[{\"deviceUserId\":\"42\",\"occurredAt\":\"" + t.toString() + "\",\"direction\":\"ENTRY\"},"
                + "{\"deviceUserId\":\"42\",\"occurredAt\":\"" + t.plusSeconds(5).atZone(BusinessDates.ZONE).toOffsetDateTime()
                + "\",\"direction\":\"EXIT\",\"method\":\"CARD\"},"
                + "{\"deviceUserId\":\"42\",\"occurredAt\":\"yesterday\"},"
                + "{\"deviceUserId\":\"42\",\"occurredAt\":\"" + t + "\",\"method\":\"BUTTON\"},"
                + "{\"deviceUserId\":\"999\",\"occurredAt\":\"" + t + "\"}]}";

        Api.Response first = api.post("/api/v1/access/device-events", null, events, "X-Device-Key", device.apiKey());
        assertThat(first.status()).as(first.body().toString()).isEqualTo(200);
        assertThat(first.body().get("stored").asInt()).isEqualTo(3);
        assertThat(first.body().get("rejected").asInt()).isEqualTo(2);
        assertThat(first.body().at("/results/0/result").asText()).isEqualTo("ALLOWED");
        assertThat(first.body().at("/results/2/status").asText()).isEqualTo("REJECTED");
        assertThat(first.body().at("/results/3/error").asText()).contains("FINGERPRINT");
        assertThat(first.body().at("/results/4/result").asText()).isEqualTo("UNKNOWN_CREDENTIAL");

        Api.Response resend = api.post("/api/v1/access/device-events", null, events, "X-Device-Key", device.apiKey());
        assertThat(resend.body().get("stored").asInt()).isZero();
        assertThat(resend.body().get("duplicates").asInt()).isEqualTo(3);
        assertThat(resend.body().at("/results/0/eventId").asLong()).isEqualTo(first.body().at("/results/0/eventId").asLong());
        assertThat(events(resident.tenantId(), "ALLOWED")).isEqualTo(2);

        assertThat(api.post("/api/v1/access/device-events", null, events).status()).isEqualTo(401);
        Api.Response wrong = api.post("/api/v1/access/device-events", null, events, "X-Device-Key", "pgd_wrong");
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(wrong.body().get("code").asText()).isEqualTo("INVALID_DEVICE_KEY");

        Api.Response rotated = api.post("/api/v1/access/devices/" + device.id() + "/rotate-key", site.managerToken(), null);
        assertThat(rotated.status()).isEqualTo(200);
        String newKey = rotated.body().get("apiKey").asText();
        assertThat(newKey).startsWith("pgd_").isNotEqualTo(device.apiKey());
        assertThat(api.post("/api/v1/access/device-events", null, events, "X-Device-Key", device.apiKey()).status()).isEqualTo(401);
        assertThat(api.post("/api/v1/access/device-events", null, events, "X-Device-Key", newKey).status()).isEqualTo(200);
        // keys are stored hashed only
        assertThat(fixtures.count("SELECT count(*) FROM access_devices WHERE api_key_hash = ? OR api_key_hash = ?", newKey,
                device.apiKey())).isZero();
        assertThat(fixtures.count("SELECT count(*) FROM audit_logs WHERE action = 'ACCESS_DEVICE_KEY_ROTATED' AND entity_id = ?",
                device.id())).isEqualTo(1);
    }

    @Test
    void verifyEndpointAnswersDoorControllersAndCanRecord() {
        Site site = site();
        settings(site, true, "DEVICE_OR_BUTTON", 1);
        Device device = device(site, "ENTRY", "GENERIC_WEBHOOK");
        Resident resident = resident(site, "77");

        Api.Response dry = api.post("/api/v1/access/verify", null, "{\"deviceUserId\":\"77\"}", "X-Device-Key", device.apiKey());
        assertThat(dry.status()).as(dry.body().toString()).isEqualTo(200);
        assertThat(dry.body().get("allowed").asBoolean()).isTrue();
        assertThat(dry.body().get("residentName").asText()).startsWith("Tenant");
        assertThat(dry.body().get("entriesToday").asInt()).isZero();
        assertThat(dry.body().get("limit").asInt()).isEqualTo(1);
        assertThat(dry.body().has("eventId")).isFalse();
        assertThat(fixtures.count("SELECT count(*) FROM access_events WHERE tenant_id = ?", resident.tenantId())).isZero();

        Api.Response recorded = api.post("/api/v1/access/verify", null, "{\"deviceUserId\":\"77\",\"record\":true}",
                "X-Device-Key", device.apiKey());
        assertThat(recorded.body().get("allowed").asBoolean()).isTrue();
        assertThat(recorded.body().get("eventId").asLong()).isPositive();

        Api.Response limited = api.post("/api/v1/access/verify", null, "{\"deviceUserId\":\"77\"}", "X-Device-Key", device.apiKey());
        assertThat(limited.body().get("allowed").asBoolean()).isFalse();
        assertThat(limited.body().get("result").asText()).isEqualTo("DENIED_LIMIT_EXCEEDED");
        assertThat(limited.body().get("entriesToday").asInt()).isEqualTo(1);

        Api.Response unknown = api.post("/api/v1/access/verify", null, "{\"deviceUserId\":\"5000\"}", "X-Device-Key",
                device.apiKey());
        assertThat(unknown.body().get("result").asText()).isEqualTo("UNKNOWN_CREDENTIAL");
        assertThat(api.post("/api/v1/access/verify", null, "{\"deviceUserId\":\"77\"}", "X-Device-Key", "nope").status())
                .isEqualTo(401);
    }

    // ---- presence & resident status ----

    @Test
    void presenceListsWhoIsInsideIncludingTrackedStaff() {
        Site site = site();
        settings(site, true, "DEVICE_OR_BUTTON", 5);
        Device device = device(site, "BOTH");
        Resident inside = resident(site, "11");
        Resident left = resident(site, "12");
        Resident movedOut = resident(site, "13");
        long staff = fixtures.user(site.org(), 4, "cook");
        fixtures.assign(staff, site.propertyId());
        assertThat(api.post("/api/v1/access/credentials", site.managerToken(), "{\"propertyId\":" + site.propertyId()
                + ",\"userId\":" + staff + ",\"deviceUserId\":\"900\",\"credentialType\":\"FACE\"}").status()).isEqualTo(201);
        LocalDateTime t = base();
        String body = String.join("\n", line("11", t, 0, 1), line("12", t, 0, 1), line("12", t.plusSeconds(9), 1, 1),
                line("13", t, 0, 1), line("900", t.plusSeconds(1), 0, 15));

        attlog(device.serial(), body, 200);
        // staff tracking is off: the cook's punch is stored but not allowed
        assertThat(fixtures.count("SELECT count(*) FROM access_events WHERE user_id = ? AND result = 'DENIED_NOT_RESIDENT'", staff))
                .isEqualTo(1);
        fixtures.jdbc().update("UPDATE tenants SET status = 'MOVED_OUT' WHERE id = ?", movedOut.tenantId());

        JsonNode presence = api.get("/api/v1/access/presence?propertyId=" + site.propertyId(), site.managerToken()).body();
        assertThat(presence.get("insideCount").asInt()).isEqualTo(1);
        assertThat(presence.at("/inside/0/tenantId").asLong()).isEqualTo(inside.tenantId());
        assertThat(presence.at("/inside/0/personType").asText()).isEqualTo("RESIDENT");
        assertThat(presence.at("/inside/0/deviceName").asText()).isEqualTo("Main gate");

        settings(site, true, "DEVICE_OR_BUTTON", 5, true);
        attlog(device.serial(), line("900", t.plusSeconds(2), 0, 15), 200);
        JsonNode withStaff = api.get("/api/v1/access/presence?propertyId=" + site.propertyId(), site.managerToken()).body();
        assertThat(withStaff.get("insideCount").asInt()).isEqualTo(2);
        assertThat(withStaff.findValuesAsText("personType")).contains("STAFF");

        JsonNode me = api.get("/api/v1/access/me", inside.token()).body();
        assertThat(me.get("enabled").asBoolean()).isTrue();
        assertThat(me.get("presence").asText()).isEqualTo("IN");
        assertThat(me.get("entriesToday").asInt()).isEqualTo(1);
        assertThat(me.get("remainingEntries").asInt()).isEqualTo(4);
        assertThat(me.get("lastEvent").get("direction").asText()).isEqualTo("ENTRY");
        assertThat(api.get("/api/v1/access/me", left.token()).body().get("presence").asText()).isEqualTo("OUT");

        // unknown PIN and moved-out resident
        attlog(device.serial(), line("4040", t.plusSeconds(3), 0, 1) + "\n" + line("13", t.plusSeconds(3), 0, 1), 200);
        assertThat(fixtures.count("SELECT count(*) FROM access_events WHERE device_id = ? AND device_user_id = '4040' "
                + "AND result = 'UNKNOWN_CREDENTIAL'", device.id())).isEqualTo(1);
        assertThat(events(movedOut.tenantId(), "DENIED_NOT_RESIDENT")).isEqualTo(1);
    }

    // ---- module switched off ----

    @Test
    void disabledModuleStoresEventsAsDeniedDisabledWithoutAlerts() {
        Site site = site();
        Device device = device(site, "ENTRY");
        Resident resident = resident(site, "21");
        JsonNode defaults = api.get("/api/v1/properties/" + site.propertyId() + "/access-settings", site.managerToken()).body();
        assertThat(defaults.get("enabled").asBoolean()).isFalse();
        assertThat(defaults.get("configured").asBoolean()).isFalse();

        LocalDateTime t = base();
        assertThat(attlog(device.serial(), line("21", t, 0, 1) + "\n" + line("21", t.plusSeconds(1), 0, 1), 200)).isEqualTo("OK: 2");
        assertThat(events(resident.tenantId(), "DENIED_DISABLED")).isEqualTo(2);
        assertThat(fixtures.count("SELECT count(*) FROM notifications WHERE type LIKE 'ACCESS_%' AND organization_id = ?",
                site.org().id())).isZero();

        JsonNode me = api.get("/api/v1/access/me", resident.token()).body();
        assertThat(me.get("enabled").asBoolean()).isFalse();
        assertThat(me.get("exitButtonAvailable").asBoolean()).isFalse();
        assertThat(api.post("/api/v1/access/exit", resident.token(), null).body().get("code").asText())
                .isEqualTo("ACCESS_CONTROL_DISABLED");
        assertThat(api.post("/api/v1/access/manual-entry", site.managerToken(), "{\"tenantId\":" + resident.tenantId()
                + ",\"reason\":\"testing the gate\"}").body().get("code").asText()).isEqualTo("ACCESS_CONTROL_DISABLED");

        settings(site, true, "DEVICE_OR_BUTTON", null);
        assertThat(fixtures.count("SELECT count(*) FROM audit_logs WHERE action = 'ACCESS_SETTINGS_UPDATED' AND entity_id = ?",
                site.propertyId())).isEqualTo(1);
        JsonNode configured = api.get("/api/v1/properties/" + site.propertyId() + "/access-settings", site.managerToken()).body();
        assertThat(configured.get("configured").asBoolean()).isTrue();
        assertThat(configured.has("maxEntriesPerDay")).isFalse();
    }

    // ---- offline detection ----

    @Test
    void offlineJobAlertsOncePerOutageAndHeartbeatResetsIt() {
        Site site = site();
        settings(site, true, "DEVICE_OR_BUTTON", null);
        Device silent = device(site, "ENTRY");
        Device neverSeen = device(site, "EXIT");
        fixtures.jdbc().update("UPDATE access_devices SET last_seen_at = now() - interval '1 hour' WHERE id = ?", silent.id());

        JsonNode list = api.get("/api/v1/access/devices?propertyId=" + site.propertyId() + "&sort=name", site.managerToken()).body();
        assertThat(list.get("totalElements").asInt()).isEqualTo(2);
        assertThat(list.findValuesAsText("online")).containsOnly("false");

        monitorJob.detectOfflineDevices();
        assertThat(fixtures.count("SELECT count(*) FROM notifications WHERE type = 'ACCESS_DEVICE_OFFLINE' AND reference_id = ? "
                + "AND recipient_user_id = ? AND channel = 'IN_APP'", silent.id(), site.managerId())).isEqualTo(1);
        assertThat(fixtures.count("SELECT count(*) FROM notifications WHERE type = 'ACCESS_DEVICE_OFFLINE' AND reference_id = ?",
                neverSeen.id())).isZero();

        monitorJob.detectOfflineDevices();
        assertThat(fixtures.count("SELECT count(*) FROM notifications WHERE type = 'ACCESS_DEVICE_OFFLINE' AND reference_id = ? "
                + "AND channel = 'IN_APP'", silent.id())).isEqualTo(1);

        assertThat(text(adms(get("/iclock/getrequest?SN=" + silent.serial())))).isEqualTo("OK");
        assertThat(fixtures.count("SELECT count(*) FROM access_devices WHERE id = ? AND offline_alerted_at IS NULL", silent.id()))
                .isEqualTo(1);
        assertThat(api.get("/api/v1/access/devices/" + silent.id(), site.managerToken()).body().get("online").asBoolean()).isTrue();
    }

    // ---- RBAC, scoping, enrolment rules ----

    @Test
    void rolesAndPropertyScopingAreEnforced() {
        Site site = site();
        settings(site, true, "DEVICE_OR_BUTTON", null);
        Device device = device(site, "BOTH");
        Resident me = resident(site, "1");
        Resident other = resident(site, "2");
        Fixtures.PropertyLayout unassigned = fixtures.property(site.org(), 1);
        Site foreign = site();
        long staff = fixtures.user(site.org(), 4, "staff");
        fixtures.assign(staff, site.propertyId());
        String staffToken = api.login(site.org().email("staff"));
        String settingsPath = "/api/v1/properties/" + site.propertyId() + "/access-settings";
        String settingsBody = "{\"enabled\":true,\"exitMode\":\"DEVICE\"}";

        // settings: owners write, managers read, staff/tenants neither
        assertThat(api.put(settingsPath, site.managerToken(), settingsBody).status()).isEqualTo(403);
        assertThat(api.get(settingsPath, staffToken).status()).isEqualTo(403);
        assertThat(api.get(settingsPath, me.token()).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/properties/" + unassigned.propertyId() + "/access-settings", site.managerToken()).status())
                .isEqualTo(403);
        assertThat(api.put("/api/v1/properties/" + foreign.propertyId() + "/access-settings", site.adminToken(), settingsBody)
                .status()).isEqualTo(404);
        assertThat(api.put(settingsPath, site.adminToken(), "{\"enabled\":true,\"exitMode\":\"DEVICE\",\"maxEntriesPerDay\":0}")
                .status()).isEqualTo(400);

        // devices
        assertThat(api.get("/api/v1/access/devices", staffToken).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/access/devices", me.token()).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/access/devices/" + device.id(), foreign.managerToken()).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/access/devices", foreign.adminToken()).body().get("totalElements").asInt()).isZero();
        assertThat(api.post("/api/v1/access/devices", site.managerToken(), "{\"propertyId\":" + unassigned.propertyId()
                + ",\"name\":\"X\",\"vendor\":\"ESSL_ADMS\",\"serialNumber\":\"ABC123\",\"direction\":\"ENTRY\"}").status())
                .isEqualTo(403);
        Api.Response duplicateSerial = api.post("/api/v1/access/devices", site.adminToken(), "{\"propertyId\":"
                + site.propertyId() + ",\"name\":\"X\",\"vendor\":\"ESSL_ADMS\",\"serialNumber\":\"" + device.serial()
                + "\",\"direction\":\"ENTRY\"}");
        assertThat(duplicateSerial.status()).isEqualTo(409);
        assertThat(duplicateSerial.body().get("code").asText()).isEqualTo("DEVICE_SERIAL_EXISTS");
        assertThat(api.post("/api/v1/access/devices", site.adminToken(), "{\"propertyId\":" + site.propertyId()
                + ",\"name\":\"X\",\"vendor\":\"ESSL_ADMS\",\"serialNumber\":\"TZ1234\",\"direction\":\"ENTRY\","
                + "\"timeZone\":\"Mars/Base\"}").body().get("code").asText()).isEqualTo("INVALID_TIME_ZONE");

        // credentials: active PINs are unique per property; suggested PINs are never reused
        Api.Response duplicatePin = api.post("/api/v1/access/credentials", site.managerToken(), "{\"propertyId\":"
                + site.propertyId() + ",\"tenantId\":" + other.tenantId() + ",\"deviceUserId\":\"1\"}");
        assertThat(duplicatePin.status()).isEqualTo(409);
        assertThat(duplicatePin.body().get("code").asText()).isEqualTo("DEVICE_USER_ID_IN_USE");
        assertThat(api.post("/api/v1/access/credentials", site.managerToken(), "{\"propertyId\":" + site.propertyId()
                + ",\"tenantId\":" + other.tenantId() + ",\"userId\":" + staff + "}").status()).isEqualTo(400);
        assertThat(api.post("/api/v1/access/credentials", site.managerToken(), "{\"propertyId\":" + unassigned.propertyId()
                + ",\"tenantId\":" + other.tenantId() + "}").status()).isEqualTo(403);
        assertThat(api.get("/api/v1/access/credentials/next-pin?propertyId=" + site.propertyId(), site.managerToken()).body()
                .get("deviceUserId").asText()).isEqualTo("3");
        Api.Response auto = api.post("/api/v1/access/credentials", site.managerToken(), "{\"propertyId\":" + site.propertyId()
                + ",\"tenantId\":" + other.tenantId() + ",\"credentialType\":\"CARD\"}");
        assertThat(auto.status()).isEqualTo(201);
        assertThat(auto.body().get("deviceUserId").asText()).isEqualTo("3");
        long autoId = auto.body().get("id").asLong();
        assertThat(api.delete("/api/v1/access/credentials/" + autoId, site.managerToken()).status()).isEqualTo(204);
        assertThat(api.get("/api/v1/access/credentials/next-pin?propertyId=" + site.propertyId(), site.managerToken()).body()
                .get("deviceUserId").asText()).isEqualTo("4");
        JsonNode credentials = api.get("/api/v1/access/credentials?propertyId=" + site.propertyId() + "&status=ACTIVE",
                site.managerToken()).body();
        assertThat(credentials.get("totalElements").asInt()).isEqualTo(2);
        assertThat(credentials.at("/content/0/personName").asText()).startsWith("Tenant");
        assertThat(api.get("/api/v1/access/credentials?sort=tenantId", site.managerToken()).status()).isEqualTo(400);

        // gate log: residents see only their own events; staff see their property; others nothing
        LocalDateTime t = base();
        attlog(device.serial(), line("1", t, 0, 1) + "\n" + line("2", t, 0, 1), 200);
        JsonNode mine = api.get("/api/v1/access/events?tenantId=" + other.tenantId(), me.token()).body();
        assertThat(mine.get("totalElements").asInt()).isEqualTo(1);
        assertThat(mine.at("/content/0/tenantId").asLong()).isEqualTo(me.tenantId());
        assertThat(api.get("/api/v1/access/events?propertyId=" + site.propertyId(), staffToken).body().get("totalElements").asInt())
                .isEqualTo(2);
        assertThat(api.get("/api/v1/access/events?propertyId=" + site.propertyId(), foreign.adminToken()).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/access/events?sort=reason", site.managerToken()).status()).isEqualTo(400);
        assertThat(api.get("/api/v1/access/events?from=" + LocalDate.of(2026, 2, 2) + "&to=" + LocalDate.of(2026, 2, 1),
                site.managerToken()).status()).isEqualTo(400);

        // presence: operators only, scoped
        assertThat(api.get("/api/v1/access/presence?propertyId=" + site.propertyId(), staffToken).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/access/presence?propertyId=" + site.propertyId(), me.token()).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/access/presence?propertyId=" + site.propertyId(), foreign.managerToken()).status())
                .isEqualTo(404);
        assertThat(api.get("/api/v1/access/me", site.managerToken()).status()).isEqualTo(403);
        // staff cannot record an exit for a resident of a property they are not assigned to
        assertThat(api.post("/api/v1/access/exit", foreign.managerToken(), "{\"tenantId\":" + me.tenantId() + "}").status())
                .isEqualTo(404);
        assertThat(api.get("/api/v1/tenants/" + me.tenantId() + "/access-limit", foreign.managerToken()).status()).isEqualTo(404);
        assertThat(api.put("/api/v1/tenants/" + me.tenantId() + "/access-limit", staffToken, "{\"unlimited\":true}").status())
                .isEqualTo(403);

        // device deletion frees the serial
        assertThat(api.delete("/api/v1/access/devices/" + device.id(), site.managerToken()).status()).isEqualTo(204);
        assertThat(adms(get("/iclock/cdata?SN=" + device.serial() + "&options=all")).getResponse().getStatus()).isEqualTo(401);
        assertThat(fixtures.count("SELECT count(*) FROM audit_logs WHERE entity_type = 'ACCESS_DEVICE' AND entity_id = ? "
                + "AND action IN ('ACCESS_DEVICE_REGISTERED', 'ACCESS_DEVICE_DELETED')", device.id())).isEqualTo(2);
    }

    @Test
    void checkOutRevokesTheResidentsCredentials() {
        Site site = site();
        Resident resident = resident(site, "61");
        transactions.executeWithoutResult(status -> credentialService.on(new BookingEvents.TenantCheckedOut(1L, site.org().id(),
                site.propertyId(), resident.tenantId(), 1L, BusinessDates.today())));
        assertThat(fixtures.count("SELECT count(*) FROM access_credentials WHERE tenant_id = ? AND status = 'REVOKED'",
                resident.tenantId())).isEqualTo(1);
        // the PIN can now be given to someone else
        Resident next = resident(site, "61");
        assertThat(next.pin()).isEqualTo("61");
    }
}
