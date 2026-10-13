package com.pgms.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pgms.audit.AuditService;
import com.pgms.booking.Allocation;
import com.pgms.booking.AllocationRepository;
import com.pgms.common.util.ReferenceNumberGenerator;
import com.pgms.config.AppProperties;
import com.pgms.property.PropertyRepository;
import com.pgms.security.AccessGuard;
import com.pgms.security.CurrentUserProvider;
import com.pgms.tenant.TenantService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class InvoiceServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private InvoiceService serviceAt(Instant now) {
        Clock clock = Clock.fixed(now, IST);
        AppProperties properties = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        when(properties.billing().currency()).thenReturn("INR");
        ReferenceNumberGenerator numbers = mock(ReferenceNumberGenerator.class);
        when(numbers.next(any())).thenReturn("INV-1");
        CurrentUserProvider users = mock(CurrentUserProvider.class);
        when(users.currentUserId()).thenReturn(Optional.of(7L));
        return new InvoiceService(mock(InvoiceRepository.class), mock(PaymentRepository.class),
                mock(AllocationRepository.class), mock(PropertyRepository.class), new InvoiceFactory(numbers, properties, clock),
                mock(TenantService.class), mock(AccessGuard.class), users, mock(AuditService.class),
                mock(ApplicationEventPublisher.class), mock(BillingPolicyService.class), mock(RecurringChargeService.class), clock);
    }

    /** Jan 30 recorded on Mar 1 (31 days back): the daily job only bills March, so February must come from check-in. */
    @Test
    void checkInBackDatedAcrossTwoMonthBoundariesBillsTheMonthInBetween() {
        InvoiceService service = serviceAt(Instant.parse("2026-03-01T05:00:00Z"));
        Allocation allocation = new Allocation(1L, 2L, 3L, 4L, null, null, LocalDate.of(2026, 1, 30),
                new BigDecimal("9300"), new BigDecimal("18000"));

        List<Invoice> invoices = service.issueMoveInInvoices(allocation, 5);

        assertThat(invoices).extracting(Invoice::getInvoiceType)
                .containsExactly(InvoiceType.SECURITY_DEPOSIT, InvoiceType.RENT, InvoiceType.RENT, InvoiceType.RENT);
        List<Invoice> rent = invoices.subList(1, 4);
        assertThat(rent).extracting(Invoice::getBillingMonth)
                .containsExactly(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 1));
        assertThat(rent).extracting(Invoice::getTotalAmount).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("600.00"), new BigDecimal("9300.00"), new BigDecimal("9300.00"));
        // never due before it is issued (today)
        assertThat(rent).allSatisfy(i -> assertThat(i.getDueDate()).isAfterOrEqualTo(LocalDate.of(2026, 3, 1)));
    }

    @Test
    void sameDayCheckInBillsOnlyTheFirstMonth() {
        InvoiceService service = serviceAt(Instant.parse("2026-10-13T05:00:00Z"));
        Allocation allocation = new Allocation(1L, 2L, 3L, 4L, null, null, LocalDate.of(2026, 10, 13),
                new BigDecimal("9300"), BigDecimal.ZERO);

        assertThat(service.issueMoveInInvoices(allocation, 5)).singleElement()
                .satisfies(i -> assertThat(i.getBillingMonth()).isEqualTo(LocalDate.of(2026, 10, 1)));
    }
}
