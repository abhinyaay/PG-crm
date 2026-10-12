package com.pgms.billing;

import com.pgms.booking.Allocation;
import com.pgms.common.util.ReferenceNumberGenerator;
import com.pgms.config.AppProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Builds rent and deposit invoices with consistent numbering, periods and due dates. */
@Component
public class InvoiceFactory {

    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    private final ReferenceNumberGenerator referenceNumbers;
    private final String currency;
    private final Clock clock;

    public InvoiceFactory(ReferenceNumberGenerator referenceNumbers, AppProperties properties, Clock clock) {
        this.referenceNumbers = referenceNumbers;
        this.currency = properties.billing().currency();
        this.clock = clock;
    }

    public Optional<Invoice> depositInvoice(Allocation allocation, LocalDate issueDate, Long userId) {
        if (allocation.getSecurityDeposit().signum() <= 0) {
            return Optional.empty();
        }
        Invoice invoice = new Invoice(referenceNumbers.next(ReferenceNumberGenerator.Kind.INVOICE), allocation.getOrganizationId(),
                allocation.getPropertyId(), allocation.getTenantId(), allocation.getId(), InvoiceType.SECURITY_DEPOSIT,
                null, null, null, issueDate, issueDate, currency);
        invoice.addCharge(LineItemType.SECURITY_DEPOSIT, "Refundable security deposit", allocation.getSecurityDeposit(),
                clock.instant(), userId, LocalDate.now(clock));
        return Optional.of(invoice);
    }

    /**
     * Rent invoice for {@code month}. When the allocation starts inside the month (move-in), the amount is
     * prorated from the start date; otherwise the full monthly rent is billed.
     */
    public Optional<Invoice> rentInvoice(Allocation allocation, YearMonth month, int rentDueDay, LocalDate issueDate,
                                         Long userId) {
        LocalDate periodStart = allocation.getStartDate().isAfter(month.atDay(1)) ? allocation.getStartDate() : month.atDay(1);
        BigDecimal amount = RentCalculator.proratedRent(allocation.getMonthlyRent(), periodStart);
        if (amount.signum() <= 0) {
            return Optional.empty();
        }
        Invoice invoice = new Invoice(referenceNumbers.next(ReferenceNumberGenerator.Kind.INVOICE), allocation.getOrganizationId(),
                allocation.getPropertyId(), allocation.getTenantId(), allocation.getId(), InvoiceType.RENT, month.atDay(1),
                periodStart, month.atEndOfMonth(), issueDate, RentCalculator.dueDate(month, rentDueDay, issueDate), currency);
        String description = periodStart.getDayOfMonth() == 1
                ? "Rent for " + MONTH_LABEL.format(month)
                : "Prorated rent " + periodStart + " to " + month.atEndOfMonth();
        invoice.addCharge(LineItemType.RENT, description, amount, clock.instant(), userId, LocalDate.now(clock));
        return Optional.of(invoice);
    }
}
