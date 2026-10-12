package com.pgms.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pgms.common.exception.BusinessRuleException;
import com.pgms.common.exception.ConflictException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InvoiceTest {

    private static final LocalDate ISSUE = LocalDate.of(2026, 10, 1);
    private static final LocalDate DUE = LocalDate.of(2026, 10, 5);
    private static final Instant NOW = Instant.parse("2026-10-01T04:00:00Z");

    private Invoice invoice;

    @BeforeEach
    void setUp() {
        invoice = new Invoice("INV-1", 1L, 1L, 1L, 1L, InvoiceType.RENT, ISSUE, ISSUE, ISSUE.withDayOfMonth(31), ISSUE, DUE, "INR");
        invoice.addCharge(LineItemType.RENT, "Rent", new BigDecimal("10000.00"), NOW, 1L, ISSUE);
    }

    @Test
    void newInvoiceIsIssuedWithTotalsFromLineItems() {
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.ISSUED);
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("10000.00");
        assertThat(invoice.balance()).isEqualByComparingTo("10000.00");
    }

    @Test
    void partialThenFullPaymentMovesToPaid() {
        invoice.applyPayment(new BigDecimal("4000"), ISSUE);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
        invoice.applyPayment(new BigDecimal("6000"), ISSUE);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(invoice.balance()).isEqualByComparingTo("0");
    }

    @Test
    void overpaymentIsRejected() {
        assertThatThrownBy(() -> invoice.applyPayment(new BigDecimal("10000.01"), ISSUE))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("exceeds the outstanding balance");
        assertThat(invoice.getAmountPaid()).isEqualByComparingTo("0");
    }

    @Test
    void paymentOnPaidInvoiceIsAConflict() {
        invoice.applyPayment(new BigDecimal("10000"), ISSUE);
        assertThatThrownBy(() -> invoice.applyPayment(BigDecimal.ONE, ISSUE)).isInstanceOf(ConflictException.class);
    }

    @Test
    void additionalChargeReopensAPaidInvoice() {
        invoice.applyPayment(new BigDecimal("10000"), ISSUE);
        invoice.addCharge(LineItemType.ELECTRICITY, "Electricity Oct", new BigDecimal("750"), NOW, 1L, ISSUE);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
        assertThat(invoice.balance()).isEqualByComparingTo("750");
    }

    @Test
    void discountReducesTotalButNeverBelowWhatWasPaid() {
        invoice.applyPayment(new BigDecimal("9000"), ISSUE);
        assertThatThrownBy(() -> invoice.applyDiscount("Goodwill", new BigDecimal("1500"), NOW, 1L, ISSUE))
                .isInstanceOf(BusinessRuleException.class);
        invoice.applyDiscount("Goodwill", new BigDecimal("1000"), NOW, 1L, ISSUE);
        assertThat(invoice.getDiscountTotal()).isEqualByComparingTo("1000");
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("9000");
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    void becomesOverdueAfterDueDateAndStaysOverdueWhenPartiallyPaid() {
        assertThat(invoice.markOverdueIfDue(DUE)).isFalse();
        assertThat(invoice.markOverdueIfDue(DUE.plusDays(1))).isTrue();
        invoice.applyPayment(new BigDecimal("100"), DUE.plusDays(2));
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.OVERDUE);
    }

    @Test
    void cannotVoidAnInvoiceWithPayments() {
        invoice.applyPayment(new BigDecimal("1"), ISSUE);
        assertThatThrownBy(() -> invoice.voidInvoice("mistake", NOW)).isInstanceOf(ConflictException.class);
    }

    @Test
    void voidInvoiceRejectsFurtherChanges() {
        invoice.voidInvoice("duplicate", NOW);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.VOID);
        assertThatThrownBy(() -> invoice.applyPayment(BigDecimal.ONE, ISSUE)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> invoice.addCharge(LineItemType.OTHER, "x", BigDecimal.ONE, NOW, 1L, ISSUE))
                .isInstanceOf(ConflictException.class);
    }

    /**
     * Everything paid against a deposit invoice counts as "deposit held" and is refunded at move-out, so extra charges
     * must not be booked there - and a deposit line on a rent invoice would be counted as rent revenue.
     */
    @Test
    void depositAndRentLinesDoNotMix() {
        Invoice deposit = new Invoice("INV-2", 1L, 1L, 1L, 1L, InvoiceType.SECURITY_DEPOSIT, null, null, null, ISSUE, ISSUE,
                "INR");
        deposit.addCharge(LineItemType.SECURITY_DEPOSIT, "Deposit", new BigDecimal("18000"), NOW, 1L, ISSUE);
        assertThatThrownBy(() -> deposit.addCharge(LineItemType.ELECTRICITY, "Meter", new BigDecimal("500"), NOW, 1L, ISSUE))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> invoice.addCharge(LineItemType.SECURITY_DEPOSIT, "Top-up", new BigDecimal("500"), NOW, 1L,
                ISSUE)).isInstanceOf(BusinessRuleException.class);
        assertThat(deposit.getTotalAmount()).isEqualByComparingTo("18000");
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("10000");
    }
}
