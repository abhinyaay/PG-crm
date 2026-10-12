package com.pgms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pgms.integration.Api.Response;
import org.junit.jupiter.api.Test;

class AuthFlowIT extends AbstractIntegrationTest {

    @Test
    void loginMeRefreshAndReuseDetection() {
        Fixtures.Org org = fixtures.organization();
        fixtures.user(org, 2, "admin");

        Response login = api.post("/api/v1/auth/login", null,
                "{\"email\":\"" + org.email("ADMIN").toUpperCase() + "\",\"password\":\"" + Fixtures.PASSWORD + "\"}");
        assertThat(login.status()).isEqualTo(200);
        String access = login.body().get("accessToken").asText();
        String refresh = login.body().get("refreshToken").asText();
        assertThat(login.body().at("/user/role").asText()).isEqualTo("PROPERTY_ADMIN");

        assertThat(api.get("/api/v1/auth/me", access).body().get("email").asText()).isEqualTo(org.email("admin"));

        Response rotated = api.post("/api/v1/auth/refresh", null, "{\"refreshToken\":\"" + refresh + "\"}");
        assertThat(rotated.status()).isEqualTo(200);
        String newRefresh = rotated.body().get("refreshToken").asText();
        assertThat(newRefresh).isNotEqualTo(refresh);

        // replaying the old token = theft signal: 401 and the whole family (including the new token) is revoked
        Response replay = api.post("/api/v1/auth/refresh", null, "{\"refreshToken\":\"" + refresh + "\"}");
        assertThat(replay.status()).isEqualTo(401);
        assertThat(replay.body().get("code").asText()).isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(api.post("/api/v1/auth/refresh", null, "{\"refreshToken\":\"" + newRefresh + "\"}").status()).isEqualTo(401);
    }

    @Test
    void logoutRevokesTheAccessTokenImmediately() {
        Fixtures.Org org = fixtures.organization();
        fixtures.user(org, 3, "manager");
        Response login = api.post("/api/v1/auth/login", null,
                "{\"email\":\"" + org.email("manager") + "\",\"password\":\"" + Fixtures.PASSWORD + "\"}");
        String access = login.body().get("accessToken").asText();
        String refresh = login.body().get("refreshToken").asText();

        assertThat(api.get("/api/v1/notifications", access).status()).isEqualTo(200);
        assertThat(api.post("/api/v1/auth/logout", access, "{\"refreshToken\":\"" + refresh + "\"}").status()).isEqualTo(204);
        assertThat(api.get("/api/v1/notifications", access).status()).isEqualTo(401);
        assertThat(api.post("/api/v1/auth/refresh", null, "{\"refreshToken\":\"" + refresh + "\"}").status()).isEqualTo(401);
    }

    @Test
    void deactivatedUserIsLockedOutIncludingLiveTokens() throws Exception {
        Fixtures.Org org = fixtures.organization();
        fixtures.user(org, 2, "admin");
        long staffId = fixtures.user(org, 4, "staff");
        String staffToken = api.login(org.email("staff"));
        String adminToken = api.login(org.email("admin"));
        // tokens carry second-resolution iat; make sure deactivation happens strictly after issuance
        Thread.sleep(1100);

        assertThat(api.post("/api/v1/users/" + staffId + "/deactivate", adminToken, null).status()).isEqualTo(200);

        assertThat(api.get("/api/v1/notifications", staffToken).status()).isEqualTo(401);
        Response relogin = api.post("/api/v1/auth/login", null,
                "{\"email\":\"" + org.email("staff") + "\",\"password\":\"" + Fixtures.PASSWORD + "\"}");
        assertThat(relogin.status()).isEqualTo(401);
        assertThat(relogin.body().get("code").asText()).isEqualTo("ACCOUNT_INACTIVE");
    }

    @Test
    void invalidCredentialsAndValidationUseTheStandardErrorEnvelope() {
        Response wrong = api.post("/api/v1/auth/login", null, "{\"email\":\"nobody@x.test\",\"password\":\"whatever1\"}");
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(wrong.body().get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(wrong.body().has("correlationId")).isTrue();

        Response invalid = api.post("/api/v1/auth/login", null, "{\"email\":\"not-an-email\",\"password\":\"\"}");
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.body().get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(invalid.body().get("fieldErrors")).hasSize(2);
        // rejected password values are never echoed back
        invalid.body().get("fieldErrors").forEach(f -> {
            if (f.get("field").asText().equals("password")) {
                assertThat(f.has("rejectedValue")).isFalse();
            }
        });
    }

    @Test
    void jwksIsPublicAndTamperedTokensAreRejected() {
        Response jwks = api.get("/api/v1/auth/jwks", null);
        assertThat(jwks.status()).isEqualTo(200);
        assertThat(jwks.body().at("/keys/0/kty").asText()).isEqualTo("RSA");
        assertThat(jwks.body().at("/keys/0/d").isMissingNode()).isTrue(); // private part never published

        Fixtures.Org org = fixtures.organization();
        fixtures.user(org, 4, "staff");
        String token = api.login(org.email("staff"));
        String[] parts = token.split("\\.");
        String forged = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AA." + parts[2];
        assertThat(api.get("/api/v1/notifications", forged).status()).isEqualTo(401);
    }
}
