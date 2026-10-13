package com.pgms.access;

import com.pgms.common.util.Hashing;
import java.security.SecureRandom;
import java.util.Base64;

/** Bridge / door-controller keys: 256 random bits, shown once; only the SHA-256 hash is stored. */
final class DeviceKeys {

    static final String PREFIX = "pgd_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private DeviceKeys() {
    }

    static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String key) {
        return Hashing.sha256Hex(key.trim());
    }
}
