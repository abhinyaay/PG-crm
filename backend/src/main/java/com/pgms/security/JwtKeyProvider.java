package com.pgms.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.pgms.config.AppProperties;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Supplies the RS256 signing key. Asymmetric keys mean only this service can mint tokens, while any other service
 * (the Python analytics API) verifies them using the public JWKS - no shared secret to leak or rotate everywhere.
 */
@Component
public class JwtKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyProvider.class);

    private final RSAKey rsaKey;

    public JwtKeyProvider(AppProperties properties) {
        AppProperties.Jwt jwt = properties.security().jwt();
        if (StringUtils.hasText(jwt.privateKey()) && StringUtils.hasText(jwt.publicKey())) {
            this.rsaKey = new RSAKey.Builder(parsePublicKey(jwt.publicKey()))
                    .privateKey(parsePrivateKey(jwt.privateKey()))
                    .keyID(jwt.keyId())
                    .build();
        } else if (jwt.allowEphemeralKeys()) {
            log.warn("No JWT key pair configured - generating an EPHEMERAL key pair. Tokens will not survive a restart "
                    + "and will not validate across replicas. Never use this outside local development.");
            KeyPair pair = generate();
            this.rsaKey = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(jwt.keyId())
                    .build();
        } else {
            throw new IllegalStateException(
                    "JWT key pair missing: set APP_JWT_PRIVATE_KEY and APP_JWT_PUBLIC_KEY (PEM) or enable ephemeral keys in dev");
        }
    }

    public RSAKey rsaKey() {
        return rsaKey;
    }

    public RSAPublicKey publicKey() {
        try {
            return rsaKey.toRSAPublicKey();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static RSAPrivateKey parsePrivateKey(String pem) {
        try {
            byte[] der = decodePem(pem, "PRIVATE KEY");
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("Invalid RSA private key (expected PKCS#8 PEM)", e);
        }
    }

    static RSAPublicKey parsePublicKey(String pem) {
        try {
            byte[] der = decodePem(pem, "PUBLIC KEY");
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("Invalid RSA public key (expected X.509 PEM)", e);
        }
    }

    private static byte[] decodePem(String pem, String type) {
        String body = pem.replace("\\n", "\n")
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
