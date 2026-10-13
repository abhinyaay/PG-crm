package com.pgms.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pgms.deposit.DepositMath.Due;
import com.pgms.deposit.DepositMath.Fund;
import com.pgms.deposit.DepositMath.Slice;
import com.pgms.deposit.DepositMath.Waterfall;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class DepositMathTest {

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private static Fund deposit(long paymentId, String amount) {
        return new Fund(paymentId, money(amount), null);
    }

    @Test
    void depositCoversDuesThenDeductionsAndTheRestIsRefundDue() {
        // deposit 20,000; rent dues 5,000; deductions (settlement invoice, newest) 1,500 -> refund 13,500
        Waterfall w = DepositMath.waterfall(List.of(deposit(1, "20000")),
                List.of(new Due(10L, money("5000")), new Due(11L, money("1500"))));
        assertThat(w.applied()).isEqualByComparingTo("6500");
        assertThat(w.fundsLeft()).isEqualByComparingTo("13500");
        assertThat(w.duesLeft()).isEqualByComparingTo("0");
        assertThat(w.slices()).extracting(Slice::invoiceId).containsExactly(10L, 11L);
    }

    @Test
    void depositShortOfDuesLeavesAnAmountPayableByTheTenant() {
        // deposit 3,000; dues 5,000 -> payable 2,000, nothing to refund
        Waterfall w = DepositMath.waterfall(List.of(deposit(1, "3000")), List.of(new Due(10L, money("5000"))));
        assertThat(w.fundsLeft()).isEqualByComparingTo("0");
        assertThat(w.duesLeft()).isEqualByComparingTo("2000");
        assertThat(w.slices()).singleElement().satisfies(s -> assertThat(s.amount()).isEqualByComparingTo("3000"));
    }

    @Test
    void oldestDueIsPaidFirstAndADueCanSpanSeveralFunds() {
        Fund original = deposit(1, "1000");
        Fund topUp = deposit(2, "4000");
        Fund credit = new Fund(3L, money("600"), 99L);
        Waterfall w = DepositMath.waterfall(List.of(original, topUp, credit),
                List.of(new Due(10L, money("2500")), new Due(11L, money("3000"))));
        assertThat(w.slices()).hasSize(4);
        assertThat(w.slices().get(0)).isEqualTo(new Slice(original, 10L, money("1000")));
        assertThat(w.slices().get(1)).isEqualTo(new Slice(topUp, 10L, money("1500")));
        assertThat(w.slices().get(2)).isEqualTo(new Slice(topUp, 11L, money("2500")));
        assertThat(w.slices().get(3).fund().isCredit()).isTrue();
        assertThat(w.slices().get(3).amount()).isEqualByComparingTo("500");
        assertThat(w.fundsLeft()).isEqualByComparingTo("100");
        assertThat(w.duesLeft()).isEqualByComparingTo("0");
    }

    @Test
    void nothingHeldMeansEverythingIsPayable() {
        Waterfall w = DepositMath.waterfall(List.of(), List.of(new Due(10L, money("700")), new Due(11L, money("300"))));
        assertThat(w.slices()).isEmpty();
        assertThat(w.duesLeft()).isEqualByComparingTo("1000");
    }

    @Test
    void drawSplitsAPayoutAcrossFundsInOrder() {
        List<Slice> slices = DepositMath.draw(List.of(deposit(1, "1000"), deposit(2, "500")), money("1200"));
        assertThat(slices).extracting(s -> s.fund().paymentId()).containsExactly(1L, 2L);
        assertThat(slices.get(1).amount()).isEqualByComparingTo("200");
        assertThat(DepositMath.total(List.of(deposit(1, "1000"), deposit(2, "500")))).isEqualByComparingTo("1500");
        assertThatThrownBy(() -> DepositMath.draw(List.of(deposit(1, "100")), money("101")))
                .isInstanceOf(IllegalStateException.class);
    }
}
