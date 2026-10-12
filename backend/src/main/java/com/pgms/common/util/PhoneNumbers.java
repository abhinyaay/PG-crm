package com.pgms.common.util;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Normalises the free-form phone numbers stored on users and tenants to E.164 ({@code +919876543210}). Numbers without
 * a country code are assumed to be national numbers of {@code defaultCountryCode} (India, 91, by default).
 */
public final class PhoneNumbers {

    private static final Pattern SEPARATORS = Pattern.compile("[\\s().-]");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final int NATIONAL_LENGTH = 10;

    private PhoneNumbers() {
    }

    public static Optional<String> toE164(String raw, String defaultCountryCode) {
        if (raw == null) {
            return Optional.empty();
        }
        String compact = SEPARATORS.matcher(raw.trim()).replaceAll("");
        boolean international = false;
        if (compact.startsWith("+")) {
            compact = compact.substring(1);
            international = true;
        } else if (compact.startsWith("00")) {
            compact = compact.substring(2);
            international = true;
        }
        if (!DIGITS.matcher(compact).matches()) {
            return Optional.empty();
        }
        String digits;
        if (international) {
            digits = compact;
        } else if (compact.length() == NATIONAL_LENGTH) {
            digits = defaultCountryCode + compact;
        } else if (compact.length() == NATIONAL_LENGTH + 1 && compact.startsWith("0")) {
            // trunk prefix: 09876543210
            digits = defaultCountryCode + compact.substring(1);
        } else if (compact.length() == defaultCountryCode.length() + NATIONAL_LENGTH && compact.startsWith(defaultCountryCode)) {
            digits = compact;
        } else {
            return Optional.empty();
        }
        if (digits.length() < 8 || digits.length() > 15 || digits.startsWith("0")) {
            return Optional.empty();
        }
        return Optional.of("+" + digits);
    }

    /** The spellings a number may have been stored with, for matching it against existing records. */
    public static Set<String> storedForms(String e164, String defaultCountryCode) {
        Set<String> forms = new LinkedHashSet<>();
        forms.add(e164);
        String digits = e164.substring(1);
        forms.add(digits);
        if (digits.startsWith(defaultCountryCode) && digits.length() == defaultCountryCode.length() + NATIONAL_LENGTH) {
            String national = digits.substring(defaultCountryCode.length());
            forms.add(national);
            forms.add("0" + national);
        }
        return forms;
    }

    /** {@code +91******3210}: enough to correlate in logs without exposing the number. */
    public static String mask(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "*".repeat(phone.length() - 7) + phone.substring(phone.length() - 4);
    }
}
