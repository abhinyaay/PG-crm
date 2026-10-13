package com.pgms.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pgms.audit.AuditService;
import com.pgms.booking.AllocationRepository;
import com.pgms.tenant.TenantService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecurringChargeServiceTest {

    private static final YearMonth OCT = YearMonth.of(2026, 10);

    private final RecurringChargeRepository repository = mock(RecurringChargeRepository.class);
    private final RecurringChargeService service = new RecurringChargeService(repository, mock(AllocationRepository.class),
            mock(TenantService.class), mock(AuditService.class),
            Clock.fixed(Instant.parse("2026-10-13T05:00:00Z"), ZoneId.of("Asia/Kolkata")));

    private static Invoice rentInvoice(long allocationId, LocalDate periodStart, InvoiceType type) {
        return new Invoice("INV-1", 1L, 2L, 3L, allocationId, type, OCT.atDay(1), periodStart, OCT.atEndOfMonth(),
                periodStart, periodStart, "INR");
    }

    private static RecurringCharge charge(LineItemType type, String amount, Long allocationId, YearMonth start, YearMonth end,
                                          boolean prorate) {
        return new RecurringCharge(1L, 2L, 3L, allocationId, type, type + " plan", new BigDecimal(amount), start, end, prorate);
    }

    @Test
    void addsChargesCoveringTheMonthAndSkipsOtherAllocations() {
        when(repository.findByTenantIdAndActiveTrueOrderByIdAsc(3L)).thenReturn(List.of(
                charge(LineItemType.FOOD, "3500", null, OCT, null, false),
                charge(LineItemType.LAUNDRY, "400", 10L, OCT.minusMonths(2), OCT, false),
                charge(LineItemType.PARKING, "700", 99L, OCT, null, false),
                charge(LineItemType.INTERNET, "600", null, OCT.plusMonths(1), null, false),
                charge(LineItemType.OTHER, "100", null, OCT.minusMonths(3), OCT.minusMonths(1), false)));
        Invoice invoice = rentInvoice(10L, OCT.atDay(1), InvoiceType.RENT);

        assertThat(service.addRecurringLines(invoice, OCT, 7L)).isEqualTo(2);

        assertThat(invoice.getLineItems()).extracting(InvoiceLineItem::getLineType)
                .containsExactly(LineItemType.FOOD, LineItemType.LAUNDRY);
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("3900");
    }

    @Test
    void prorationAppliesOnlyToFlaggedChargesOnAProratedInvoice() {
        when(repository.findByTenantIdAndActiveTrueOrderByIdAsc(3L)).thenReturn(List.of(
                charge(LineItemType.FOOD, "3100", null, OCT, null, true),
                charge(LineItemType.PARKING, "620", null, OCT, null, false)));
        Invoice invoice = rentInvoice(10L, LocalDate.of(2026, 10, 22), InvoiceType.RENT);

        service.addRecurringLines(invoice, OCT, null);

        // 10 of 31 days
        assertThat(invoice.sumOf(LineItemType.FOOD)).isEqualByComparingTo("1000.00");
        assertThat(invoice.getLineItems().get(0).getDescription()).contains("prorated from 2026-10-22");
        assertThat(invoice.sumOf(LineItemType.PARKING)).isEqualByComparingTo("620");
    }

    @Test
    void depositInvoicesNeverGetRecurringLines() {
        Invoice deposit = rentInvoice(10L, OCT.atDay(1), InvoiceType.SECURITY_DEPOSIT);
        assertThat(service.addRecurringLines(deposit, OCT, null)).isZero();
    }

    @Test
    void endedChargesNoLongerCoverAnyMonth() {
        RecurringCharge food = charge(LineItemType.FOOD, "3500", null, OCT, null, false);
        assertThat(food.covers(OCT)).isTrue();
        assertThat(food.covers(OCT.minusMonths(1))).isFalse();
        food.update("Veg", new BigDecimal("4000"), OCT.plusMonths(1), false, true);
        assertThat(food.covers(OCT.plusMonths(2))).isFalse();
        food.deactivate();
        assertThat(food.covers(OCT)).isFalse();
    }
}
