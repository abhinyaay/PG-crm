package com.pgms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class JwtKeyProviderTest {

    @Test
    void parsesPemKeysIncludingEscapedNewlines() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        // env vars usually carry PEMs on one line with literal "\\n" sequences
        String privatePem = ("-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----").replace("\r\n", "\n").replace("\n", "\\n");
        String publicPem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(pair.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----";

        assertThat(JwtKeyProvider.parsePrivateKey(privatePem).getModulus())
                .isEqualTo(((RSAPrivateKey) pair.getPrivate()).getModulus());
        assertThat(JwtKeyProvider.parsePublicKey(publicPem).getEncoded()).isEqualTo(pair.getPublic().getEncoded());
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> JwtKeyProvider.parsePublicKey("-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----"))
                .isInstanceOf(IllegalStateException.class);
    }
}
