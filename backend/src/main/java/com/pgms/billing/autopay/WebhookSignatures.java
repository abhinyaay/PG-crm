package com.pgms.billing.autopay;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 webhook signatures (hex), compared in constant time over the exact raw request body. */
public final class WebhookSignatures {

    private WebhookSignatures() {
    }

    public static String hmacSha256Hex(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public static boolean matches(String secret, byte[] body, String signature) {
        if (secret == null || secret.isBlank() || signature == null || signature.isBlank() || body == null) {
            return false;
        }
        byte[] expected = hmacSha256Hex(secret, body).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = signature.trim().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }
}
