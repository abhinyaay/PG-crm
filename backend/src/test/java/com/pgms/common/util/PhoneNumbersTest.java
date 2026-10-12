package com.pgms.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PhoneNumbersTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "9876543210,          +919876543210",
        "+919876543210,       +919876543210",
        "919876543210,        +919876543210",
        "09876543210,         +919876543210",
        "'+91 98765-43210',   +919876543210",
        "'(98765) 43210',     +919876543210",
        "0044 20 7946 0958,   +442079460958",
        "+14155550123,        +14155550123",
        "+9190100010000,      +9190100010000"
    })
    void normalisesToE164WithIndiaAsDefault(String raw, String expected) {
        assertThat(PhoneNumbers.toE164(raw, "91")).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "12345", "98765x43210", "+0123456789", "+1234567890123456", "1234567890123"})
    void rejectsWhatCannotBeAPhoneNumber(String raw) {
        assertThat(PhoneNumbers.toE164(raw, "91")).isEmpty();
    }

    @Test
    void nullAndStoredFormsAndMasking() {
        assertThat(PhoneNumbers.toE164(null, "91")).isEmpty();
        assertThat(PhoneNumbers.storedForms("+919876543210", "91"))
                .containsExactly("+919876543210", "919876543210", "9876543210", "09876543210");
        assertThat(PhoneNumbers.storedForms("+14155550123", "91")).containsExactly("+14155550123", "14155550123");
        assertThat(PhoneNumbers.mask("+919876543210")).isEqualTo("+91******3210");
        assertThat(PhoneNumbers.mask("123")).isEqualTo("***");
    }
}
