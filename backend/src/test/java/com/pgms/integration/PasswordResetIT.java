package com.pgms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pgms.auth.AuthMaintenanceJobs;
import jakarta.mail.internet.MimeMessage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PasswordResetIT extends AbstractIntegrationTest {

    private static final String NEW_PASSWORD = "N3wPassw0rd!";

    @Autowired
    private Outbox outbox;

    @Autowired
    private AuthMaintenanceJobs maintenanceJobs;

    @Test
    void resetByEmailCodeRevokesEverySessionAndConfirmsTheChange() throws Exception {
        Fixtures.Org org = fixtures.organization();
        long userId = fixtures.user(org, 3, "manager");
        String email = org.email("manager");
        Api.Response login = api.post("/api/v1/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"" + Fixtures.PASSWORD + "\"}");
        String access = login.body().get("accessToken").asText();
        String refresh = login.body().get("refreshToken").asText();
        Thread.sleep(1100); // access tokens carry second-resolution iat

        Api.Response accepted = api.post("/api/v1/auth/password/forgot", null, "{\"identifier\":\" " + email.toUpperCase() + "\"}");
        assertThat(accepted.status()).isEqualTo(202);
        assertThat(rows("PASSWORD_RESET_OTP", email)).isEqualTo(1);
        assertThat(fixtures.count("SELECT count(*) FROM notifications WHERE type = 'PASSWORD_RESET_OTP' AND recipient_user_id = ?",
                userId)).isZero(); // never in-app

        outbox.dispatchAll();
        List<MimeMessage> mails = outbox.emailsTo(email);
        assertThat(mails).hasSize(1);
        assertThat(mails.get(0).getSubject()).isEqualTo("Your password reset code");
        String code = Outbox.codeIn(Outbox.textParts(mails.get(0)).get("text/plain"));
        assertThat(Outbox.textParts(mails.get(0)).get("text/html")).contains(">" + code + "</div>");
        // the delivered code does not linger in the outbox
        assertThat(fixtures.jdbc().queryForMap("SELECT body, template_params FROM notifications WHERE type = 'PASSWORD_RESET_OTP' "
                + "AND recipient_address = ?", email)).containsEntry("body", "[redacted]").containsEntry("template_params", null);

        Api.Response wrong = reset(email, otherCode(code), NEW_PASSWORD);
        assertThat(wrong.status()).isEqualTo(400);
        assertThat(wrong.body().get("code").asText()).isEqualTo("INVALID_OR_EXPIRED_CODE");
        assertThat(reset(email, code, NEW_PASSWORD).status()).isEqualTo(204);

        assertThat(api.get("/api/v1/auth/me", access).status()).isEqualTo(401);
        assertThat(api.post("/api/v1/auth/refresh", null, "{\"refreshToken\":\"" + refresh + "\"}").status()).isEqualTo(401);
        assertThat(api.post("/api/v1/auth/login", null, "{\"email\":\"" + email + "\",\"password\":\"" + Fixtures.PASSWORD + "\"}")
                .status()).isEqualTo(401);
        assertThat(api.post("/api/v1/auth/login", null, "{\"email\":\"" + email + "\",\"password\":\"" + NEW_PASSWORD + "\"}")
                .status()).isEqualTo(200);
        // single use
        assertThat(reset(email, code, "Another1Passw0rd").status()).isEqualTo(400);
        // mandatory confirmation by email (+ in-app)
        assertThat(rows("PASSWORD_CHANGED", email)).isEqualTo(1);
        assertThat(fixtures.count("SELECT count(*) FROM notifications WHERE type = 'PASSWORD_CHANGED' AND recipient_user_id = ?",
                userId)).isEqualTo(1);
    }

    @Test
    void unknownIdentifiersGetTheSameAnswerAndNothingIsSent() {
        int before = fixtures.count("SELECT count(*) FROM notifications WHERE type = 'PASSWORD_RESET_OTP'");
        Fixtures.Org org = fixtures.organization();
        fixtures.user(org, 4, "staff");

        Api.Response known = api.post("/api/v1/auth/password/forgot", null, "{\"identifier\":\"" + org.email("staff") + "\"}");
        Api.Response unknown = api.post("/api/v1/auth/password/forgot", null, "{\"identifier\":\"ghost@" + org.slug() + ".test\"}");
        Api.Response notAPhone = api.post("/api/v1/auth/password/forgot", null, "{\"identifier\":\"12ab\"}");

        assertThat(List.of(known.status(), unknown.status(), notAPhone.status())).containsOnly(202);
        assertThat(unknown.body()).isEqualTo(known.body());
        assertThat(fixtures.count("SELECT count(*) FROM notifications WHERE type = 'PASSWORD_RESET_OTP'")).isEqualTo(before + 1);
        Api.Response resetUnknown = reset("ghost@" + org.slug() + ".test", "123456", NEW_PASSWORD);
        assertThat(resetUnknown.status()).isEqualTo(400);
        assertThat(resetUnknown.body().get("code").asText()).isEqualTo("INVALID_OR_EXPIRED_CODE");
    }

    @Test
    void phoneIdentifierUsesSmsAndWrongGuessesLockTheCode() {
        Fixtures.Org org = fixtures.organization();
        Fixtures.PropertyLayout layout = fixtures.property(org, 1);
        long tenant = fixtures.tenant(org, layout.propertyId());
        long userId = fixtures.user(org, 5, "resident");
        fixtures.linkTenantUser(tenant, userId); // users.phone is empty: matched through the tenant profile
        String phone = fixtures.jdbc().queryForObject("SELECT phone FROM tenants WHERE id = ?", String.class, tenant);

        assertThat(api.post("/api/v1/auth/password/forgot", null, "{\"identifier\":\"" + phone + "\"}").status()).isEqualTo(202);
        assertThat(fixtures.count("SELECT count(*) FROM notifications WHERE type = 'PASSWORD_RESET_OTP' AND recipient_address = ?",
                org.email("resident"))).isZero();
        outbox.dispatchAll();
        List<SmsGatewayStub.Request> sms = outbox.sms().requestsTo(phone.substring(1));
        assertThat(sms).hasSize(1);
        assertThat(sms.get(0).body().get("template_id").asText()).isEqualTo("1107160000000000003");
        String code = sms.get(0).recipient().get("code").asText();
        assertThat(code).matches("\\d{6}");

        for (int i = 0; i < 5; i++) {
            assertThat(reset(phone, otherCode(code), NEW_PASSWORD).status()).isEqualTo(400);
        }
        Api.Response locked = reset(phone, code, NEW_PASSWORD);
        assertThat(locked.status()).isEqualTo(400);
        assertThat(locked.body().get("code").asText()).isEqualTo("INVALID_OR_EXPIRED_CODE");
        assertThat(fixtures.count("SELECT attempts FROM password_reset_tokens WHERE user_id = ?", userId)).isEqualTo(5);
    }

    @Test
    void expiredCodesAreRejected() {
        Fixtures.Org org = fixtures.organization();
        long userId = fixtures.user(org, 2, "admin");
        String email = org.email("admin");
        api.post("/api/v1/auth/password/forgot", null, "{\"identifier\":\"" + email + "\"}");
        String code = pendingCode("PASSWORD_RESET_OTP", email);
        fixtures.jdbc().update("UPDATE password_reset_tokens SET expires_at = now() - interval '1 second' WHERE user_id = ?", userId);

        assertThat(reset(email, code, NEW_PASSWORD).status()).isEqualTo(400);
    }

    @Test
    void requestsAreThrottledPerIdentifierWhetherOrNotItExists() {
        String unknown = "nobody-" + UUID.randomUUID() + "@nowhere.test";
        for (int i = 0; i < 3; i++) {
            assertThat(api.post("/api/v1/auth/password/forgot", null, "{\"identifier\":\"" + unknown + "\"}").status())
                    .isEqualTo(202);
        }
        Api.Response limited = api.post("/api/v1/auth/password/forgot", null, "{\"identifier\":\"" + unknown.toUpperCase() + "\"}");
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.body().get("code").asText()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void portalAccountWithoutPasswordSendsASetupCodeByEmailAndSms() throws Exception {
        Fixtures.Org org = fixtures.organization();
        Fixtures.PropertyLayout layout = fixtures.property(org, 1);
        fixtures.user(org, 2, "admin");
        long tenant = fixtures.tenant(org, layout.propertyId());
        String email = fixtures.jdbc().queryForObject("SELECT email FROM tenants WHERE id = ?", String.class, tenant);
        String phone = fixtures.jdbc().queryForObject("SELECT phone FROM tenants WHERE id = ?", String.class, tenant);

        Api.Response created = api.post("/api/v1/tenants/" + tenant + "/portal-account", api.login(org.email("admin")), null);
        assertThat(created.status()).isEqualTo(200);
        assertThat(fixtures.count("SELECT count(*) FROM notifications WHERE type = 'PORTAL_ACCOUNT_CREATED' "
                + "AND recipient_address IN (?, ?)", email, phone)).isEqualTo(2);
        String code = pendingCode("PORTAL_ACCOUNT_CREATED", email);
        // nobody knows the generated password
        assertThat(api.post("/api/v1/auth/login", null, "{\"email\":\"" + email + "\",\"password\":\"" + Fixtures.PASSWORD + "\"}")
                .status()).isEqualTo(401);

        outbox.dispatchAll();
        MimeMessage invite = outbox.emailsTo(email).get(0);
        assertThat(invite.getSubject()).isEqualTo("Your tenant portal account is ready");
        assertThat(Outbox.textParts(invite).get("text/plain")).contains(code).contains("Valid for 3 days");
        assertThat(outbox.sms().requestsTo(phone.substring(1)).get(0).body().get("template_id").asText())
                .isEqualTo("1107160000000000004");

        assertThat(reset(phone, code, NEW_PASSWORD).status()).isEqualTo(204);
        assertThat(api.post("/api/v1/auth/login", null, "{\"email\":\"" + email + "\",\"password\":\"" + NEW_PASSWORD + "\"}")
                .status()).isEqualTo(200);
    }

    @Test
    void cleanupJobRemovesExpiredRefreshTokensAndOldCodes() {
        Fixtures.Org org = fixtures.organization();
        long userId = fixtures.user(org, 4, "staff");
        long expiredSuccessor = refreshToken(userId, "now() - interval '3 days'", null);
        long expiredRotated = refreshToken(userId, "now() - interval '4 days'", expiredSuccessor);
        long stillReferenced = refreshToken(userId, "now() - interval '3 days'", null);
        long live = refreshToken(userId, "now() + interval '5 days'", stillReferenced);
        fixtures.jdbc().update("INSERT INTO password_reset_tokens (user_id, channel, code_hash, expires_at, created_at) "
                + "VALUES (?, 'EMAIL', 'x', now() - interval '10 days', now() - interval '10 days')", userId);
        fixtures.jdbc().update("INSERT INTO password_reset_tokens (user_id, channel, code_hash, expires_at) "
                + "VALUES (?, 'EMAIL', 'y', now() + interval '10 minutes')", userId);

        AuthMaintenanceJobs.CleanupResult result = maintenanceJobs.purgeExpired();

        assertThat(result.refreshTokens()).isGreaterThanOrEqualTo(2);
        assertThat(fixtures.count("SELECT count(*) FROM refresh_tokens WHERE id IN (?, ?)", expiredSuccessor, expiredRotated)).isZero();
        assertThat(fixtures.count("SELECT count(*) FROM refresh_tokens WHERE id IN (?, ?)", stillReferenced, live)).isEqualTo(2);
        assertThat(fixtures.count("SELECT count(*) FROM password_reset_tokens WHERE user_id = ?", userId)).isEqualTo(1);
    }

    private Api.Response reset(String identifier, String code, String password) {
        return api.post("/api/v1/auth/password/reset", null,
                "{\"identifier\":\"" + identifier + "\",\"code\":\"" + code + "\",\"newPassword\":\"" + password + "\"}");
    }

    private int rows(String type, String address) {
        return fixtures.count("SELECT count(*) FROM notifications WHERE type = ? AND recipient_address = ?", type, address);
    }

    /** The code as stored in the (not yet dispatched) outbox row. */
    private String pendingCode(String type, String address) {
        return fixtures.jdbc().queryForObject("SELECT template_params->>'code' FROM notifications WHERE type = ? "
                + "AND recipient_address = ? ORDER BY id DESC LIMIT 1", String.class, type, address);
    }

    private long refreshToken(long userId, String expiresAtSql, Long replacedBy) {
        return fixtures.jdbc().queryForObject("INSERT INTO refresh_tokens (user_id, token_hash, family_id, expires_at, "
                + "replaced_by_token_id) VALUES (?, ?, gen_random_uuid(), " + expiresAtSql + ", ?) RETURNING id", Long.class,
                userId, UUID.randomUUID().toString().replace("-", ""), replacedBy);
    }

    private static String otherCode(String code) {
        return String.format("%06d", (Integer.parseInt(code) + 1) % 1_000_000);
    }
}
