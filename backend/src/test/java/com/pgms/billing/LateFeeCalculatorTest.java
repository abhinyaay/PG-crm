package com.pgms.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.pgms.billing.LateFeeCalculator.Assessment;
import com.pgms.billing.LateFeeCalculator.InvoiceFacts;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LateFeeCalculatorTest {

    private static final LocalDate ISSUED = LocalDate.of(2026, 10, 1);
    private static final LocalDate DUE = LocalDate.of(2026, 10, 5);

    private static BillingPolicy policy(LateFeeType type, String value, String cap, int grace, int moveInGrace, boolean onMoveIn) {
        return new BillingPolicy(1L, true, true, type, new BigDecimal(value), cap == null ? null : new BigDecimal(cap), grace,
                moveInGrace, onMoveIn, 3, List.of(1, 5), null, null, 0);
    }

    private static InvoiceFacts invoice(String charged) {
        BigDecimal alreadyCharged = new BigDecimal(charged);
        return new InvoiceFacts(ISSUED, DUE, false, new BigDecimal("9000"), alreadyCharged, alreadyCharged.signum() > 0);
    }

    @Test
    void flatFeeStartsTheDayAfterTheGracePeriod() {
        BillingPolicy flat = policy(LateFeeType.FLAT, "200", null, 3, 7, false);
        // due on the 5th + 3 grace days: the 8th is still free, the 9th is the first late day
        assertThat(LateFeeCalculator.assess(flat, invoice("0"), LocalDate.of(2026, 10, 8)).charges()).isFalse();
        Assessment first = LateFeeCalculator.assess(flat, invoice("0"), LocalDate.of(2026, 10, 9));
        assertThat(first.amount()).isEqualByComparingTo("200");
        assertThat(first.periodKey()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(first.firstLateDay()).isEqualTo(LocalDate.of(2026, 10, 9));
    }

    @Test
    void flatAndPercentFeesAreChargedOncePerInvoiceWithAStableKey() {
        BillingPolicy flat = policy(LateFeeType.FLAT, "200", null, 0, 7, false);
        Assessment later = LateFeeCalculator.assess(flat, invoice("0"), LocalDate.of(2026, 10, 20));
        assertThat(later.periodKey()).as("same key whenever the job runs").isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(LateFeeCalculator.assess(flat, invoice("200"), LocalDate.of(2026, 10, 21)).charges()).isFalse();
        BillingPolicy percent = policy(LateFeeType.PERCENT_OF_RENT, "2", null, 0, 7, false);
        assertThat(LateFeeCalculator.assess(percent, invoice("180"), LocalDate.of(2026, 10, 21)).charges()).isFalse();
    }

    @ParameterizedTest(name = "{0}% of {1} = {2}")
    @CsvSource({"2.5, 9999, 249.98", "5, 9000, 450.00", "1.25, 333.33, 4.17", "0.5, 1, 0.01"})
    void percentOfRentRoundsHalfUpToPaise(String percent, String rent, String expected) {
        BillingPolicy policy = policy(LateFeeType.PERCENT_OF_RENT, percent, null, 0, 7, false);
        InvoiceFacts facts = new InvoiceFacts(ISSUED, DUE, false, new BigDecimal(rent), BigDecimal.ZERO, false);
        assertThat(LateFeeCalculator.assess(policy, facts, DUE.plusDays(1)).amount()).isEqualByComparingTo(expected);
    }

    @Test
    void perDayFeeAccruesAndCatchesUpMissedDays() {
        BillingPolicy perDay = policy(LateFeeType.PER_DAY, "50", null, 0, 7, false);
        assertThat(LateFeeCalculator.assess(perDay, invoice("0"), DUE.plusDays(1)).amount()).isEqualByComparingTo("50");
        Assessment third = LateFeeCalculator.assess(perDay, invoice("50"), DUE.plusDays(3));
        assertThat(third.amount()).isEqualByComparingTo("100");
        assertThat(third.periodKey()).isEqualTo(DUE.plusDays(3));
        assertThat(LateFeeCalculator.assess(perDay, invoice("150"), DUE.plusDays(3)).charges()).isFalse();
    }

    @Test
    void capTruncatesAndThenExhaustsTheFee() {
        BillingPolicy capped = policy(LateFeeType.PER_DAY, "50", "120", 0, 7, false);
        assertThat(LateFeeCalculator.assess(capped, invoice("100"), DUE.plusDays(3)).amount()).isEqualByComparingTo("20");
        assertThat(LateFeeCalculator.assess(capped, invoice("120"), DUE.plusDays(10)).charges()).isFalse();
        BillingPolicy flatCapped = policy(LateFeeType.FLAT, "500", "300", 0, 7, false);
        assertThat(LateFeeCalculator.assess(flatCapped, invoice("0"), DUE.plusDays(1)).amount()).isEqualByComparingTo("300");
    }

    @Test
    void disabledPolicyAndMoveInInvoicesAreSkippedUnlessAllowed() {
        BillingPolicy disabled = new BillingPolicy(1L, true, false, LateFeeType.FLAT, new BigDecimal("200"), null, 0, 7, false, 3,
                List.of(1), null, null, 0);
        assertThat(LateFeeCalculator.assess(disabled, invoice("0"), DUE.plusDays(10)).charges()).isFalse();

        InvoiceFacts moveIn = new InvoiceFacts(ISSUED, DUE, true, new BigDecimal("4000"), BigDecimal.ZERO, false);
        assertThat(LateFeeCalculator.assess(policy(LateFeeType.FLAT, "200", null, 0, 7, false), moveIn, DUE.plusDays(10))
                .charges()).isFalse();
        assertThat(LateFeeCalculator.assess(policy(LateFeeType.FLAT, "200", null, 0, 7, true), moveIn, DUE.plusDays(10))
                .charges()).isTrue();
    }

    @Test
    void invoicesDueOnTheirIssueDateGetTheMoveInGrace() {
        LocalDate issued = LocalDate.of(2026, 10, 20);
        BillingPolicy policy = policy(LateFeeType.FLAT, "200", null, 2, 7, true);
        // check-in on the 20th after the 5th due day: due the same day; 7 days move-in grace, then 2 grace days
        assertThat(LateFeeCalculator.effectiveDueDate(policy, issued, issued)).isEqualTo(LocalDate.of(2026, 10, 27));
        assertThat(LateFeeCalculator.firstLateDay(policy, issued, issued)).isEqualTo(LocalDate.of(2026, 10, 30));
        InvoiceFacts facts = new InvoiceFacts(issued, issued, true, new BigDecimal("3000"), BigDecimal.ZERO, false);
        assertThat(LateFeeCalculator.assess(policy, facts, LocalDate.of(2026, 10, 29)).charges()).isFalse();
        assertThat(LateFeeCalculator.assess(policy, facts, LocalDate.of(2026, 10, 30)).charges()).isTrue();
        // regular invoices (due after issue) are unaffected
        assertThat(LateFeeCalculator.effectiveDueDate(policy, ISSUED, DUE)).isEqualTo(DUE);
    }
}
