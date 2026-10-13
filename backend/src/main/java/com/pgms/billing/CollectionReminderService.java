package com.pgms.billing;

import com.pgms.property.Property;
import com.pgms.property.PropertyRepository;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One reminder / digest per transaction (REQUIRES_NEW), so one bad row never rolls back the rest of a job run.
 * Every notification carries a dedup key, so re-running a job on the same day sends nothing new.
 */
@Service
public class CollectionReminderService {

    private static final int DIGEST_TOP = 5;

    private final InvoiceRepository invoiceRepository;
    private final PropertyRepository propertyRepository;
    private final BillingPolicyService policyService;
    private final ApplicationEventPublisher events;

    public CollectionReminderService(InvoiceRepository invoiceRepository, PropertyRepository propertyRepository,
                                     BillingPolicyService policyService, ApplicationEventPublisher events) {
        this.invoiceRepository = invoiceRepository;
        this.propertyRepository = propertyRepository;
        this.policyService = policyService;
        this.events = events;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean sendDueReminder(Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId).orElse(null);
        if (invoice == null || !(invoice.getStatus() == InvoiceStatus.ISSUED || invoice.getStatus() == InvoiceStatus.PARTIALLY_PAID)
                || invoice.balance().signum() <= 0) {
            return false;
        }
        events.publishEvent(new BillingEvents.PaymentDue(invoice.getId(), invoice.getInvoiceNumber(),
                invoice.getOrganizationId(), invoice.getTenantId(), invoice.balance(), invoice.getDueDate(), false));
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean sendOverdueReminder(Long invoiceId, LocalDate today) {
        Invoice invoice = invoiceRepository.findById(invoiceId).orElse(null);
        if (invoice == null || invoice.getStatus() != InvoiceStatus.OVERDUE || invoice.balance().signum() <= 0) {
            return false;
        }
        events.publishEvent(overdueEvent(invoice, policyService.effectiveFor(invoice.getPropertyId()), today));
        return true;
    }

    /** The D+n overdue notice; also published by overdue marking together with the status change. */
    static BillingEvents.OverdueReminder overdueEvent(Invoice invoice, BillingPolicy policy, LocalDate today) {
        return new BillingEvents.OverdueReminder(invoice.getId(), invoice.getInvoiceNumber(), invoice.getOrganizationId(),
                invoice.getPropertyId(), invoice.getTenantId(), invoice.balance(), invoice.getDueDate(),
                ReminderSchedule.daysOverdue(invoice.getDueDate(), today), LateFeeService.lateFeeFrom(invoice, policy));
    }

    /** {@code invoiceIds}: the property's overdue invoices with a balance (re-read here). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean sendOverdueDigest(Long propertyId, List<Long> invoiceIds, LocalDate today) {
        List<Invoice> overdue = invoiceRepository.findAllById(invoiceIds).stream()
                .filter(i -> i.getStatus() == InvoiceStatus.OVERDUE && i.balance().signum() > 0).toList();
        Property property = propertyRepository.findById(propertyId).orElse(null);
        if (overdue.isEmpty() || property == null) {
            return false;
        }
        BigDecimal total = overdue.stream().map(Invoice::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        String top = overdue.stream().sorted(Comparator.comparing(Invoice::balance).reversed()).limit(DIGEST_TOP)
                .map(i -> i.getInvoiceNumber() + " INR " + i.balance().stripTrailingZeros().toPlainString() + " ("
                        + ReminderSchedule.daysOverdue(i.getDueDate(), today) + "d)")
                .collect(Collectors.joining(", "));
        events.publishEvent(new BillingEvents.OverdueDigest(propertyId, property.getName(), property.getOrganizationId(), today,
                overdue.size(), total, top, today.getDayOfWeek() == DayOfWeek.MONDAY));
        return true;
    }
}
