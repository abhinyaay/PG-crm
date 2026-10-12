package com.pgms.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pgms.common.exception.BusinessRuleException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PaymentTest {

    private Payment payment() {
        LocalDate day = LocalDate.of(2026, 10, 1);
        Invoice invoice = new Invoice("INV-1", 1L, 1L, 1L, 1L, InvoiceType.SECURITY_DEPOSIT, null, null, null, day, day, "INR");
        return new Payment("RCPT-1", invoice, "key-12345678", "fp", new BigDecimal("20000"), PaymentMethod.UPI, null,
                Instant.now(), null);
    }

    @Test
    void partialAndFullRefundUpdateStatus() {
        Payment payment = payment();
        payment.refund(new BigDecimal("5000"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(payment.refundableAmount()).isEqualByComparingTo("15000");
        payment.refund(new BigDecimal("15000"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void cannotRefundMoreThanWasPaid() {
        Payment payment = payment();
        payment.refund(new BigDecimal("19000"));
        assertThatThrownBy(() -> payment.refund(new BigDecimal("1000.01"))).isInstanceOf(BusinessRuleException.class);
    }
}
