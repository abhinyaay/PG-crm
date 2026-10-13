package com.pgms.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pgms.common.exception.BadRequestException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class UpiLinksTest {

    @Test
    void buildsAnEncodedUpiPayUri() {
        String uri = UpiLinks.payUri("green.nest@okhdfc", "Green Nest & Co", new BigDecimal("9000.5"), "INV-202610-000042");
        assertThat(uri).isEqualTo("upi://pay?pa=green.nest@okhdfc&pn=Green%20Nest%20%26%20Co&am=9000.50&cu=INR"
                + "&tn=Rent%20INV-202610-000042&tr=INV-202610-000042");
    }

    @Test
    void encodesReservedAndNonAsciiCharactersAndTruncatesTheNote() {
        assertThat(UpiLinks.encode("a=b?c/d+e#f")).isEqualTo("a%3Db%3Fc%2Fd%2Be%23f");
        assertThat(UpiLinks.encode("Śrī PG")).isEqualTo("%C5%9Ar%C4%AB%20PG");
        String longNumber = "X".repeat(60);
        String uri = UpiLinks.payUri("pg@upi", "PG", BigDecimal.TEN, longNumber);
        String note = uri.substring(uri.indexOf("&tn=") + 4, uri.indexOf("&tr="));
        assertThat(note.replace("%20", " ")).hasSize(UpiLinks.MAX_NOTE).startsWith("Rent XXX");
        assertThat(uri).contains("&am=10.00&");
    }

    @Test
    void referencesAreValidatedPerMethod() {
        assertThat(UpiLinks.normalizeReference(PaymentMethod.UPI, " 4123 4567 8901 ", null)).isEqualTo("412345678901");
        assertThat(UpiLinks.normalizeReference(PaymentMethod.BANK_TRANSFER, "hdfcN12345", null)).isEqualTo("HDFCN12345");
        assertThat(UpiLinks.normalizeReference(PaymentMethod.CHEQUE, "004512", null)).isEqualTo("004512");
        assertThat(UpiLinks.normalizeReference(PaymentMethod.CASH, null, "Paid to warden Ravi")).isNull();

        assertThatThrownBy(() -> UpiLinks.normalizeReference(PaymentMethod.UPI, "12345678901", null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> UpiLinks.normalizeReference(PaymentMethod.UPI, null, null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> UpiLinks.normalizeReference(PaymentMethod.BANK_TRANSFER, "AB-123", null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> UpiLinks.normalizeReference(PaymentMethod.CHEQUE, "12345", null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> UpiLinks.normalizeReference(PaymentMethod.CASH, "123456789012", "note"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> UpiLinks.normalizeReference(PaymentMethod.CASH, null, " "))
                .isInstanceOf(BadRequestException.class).hasFieldOrPropertyWithValue("code", "NOTE_REQUIRED");
        assertThatThrownBy(() -> UpiLinks.normalizeReference(PaymentMethod.CARD, "123456789012", null))
                .isInstanceOf(BadRequestException.class).hasFieldOrPropertyWithValue("code", "INVALID_CLAIM_METHOD");
    }
}
