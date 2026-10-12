package com.pgms.billing;

import com.pgms.booking.AllocationRepository;
import com.pgms.common.exception.BusinessRuleException;
import com.pgms.common.exception.ConstraintViolationTranslator;
import com.pgms.config.AppProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recurring billing work. ShedLock guarantees one execution per schedule across all replicas; on top of that every
 * step is idempotent (unique indexes, status checks, notification dedup keys), so a re-run is always safe.
 */
@Component
public class BillingJobs {

    private static final Logger log = LoggerFactory.getLogger(BillingJobs.class);

    private final AllocationRepository allocationRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceService invoiceService;
    private final ApplicationEventPublisher events;
    private final AppProperties.Billing config;
    private final Clock clock;

    public BillingJobs(AllocationRepository allocationRepository, InvoiceRepository invoiceRepository,
                       InvoiceService invoiceService, ApplicationEventPublisher events, AppProperties properties, Clock clock) {
        this.allocationRepository = allocationRepository;
        this.invoiceRepository = invoiceRepository;
        this.invoiceService = invoiceService;
        this.events = events;
        this.config = properties.billing();
        this.clock = clock;
    }

    /** Daily (not only on the 1st) so a missed run or a back-dated check-in is billed on the next execution. */
    @Scheduled(cron = "${app.jobs.rent-generation-cron:0 30 0 * * *}", zone = "${app.business-timezone}")
    @SchedulerLock(name = "rentInvoiceGeneration", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void generateMonthlyRentInvoices() {
        generateRentInvoices(YearMonth.now(clock));
    }

    public BillingDtos.GenerationResult generateRentInvoices(YearMonth month) {
        if (month.isAfter(YearMonth.now(clock))) {
            // every open-ended allocation "occupies" a future 1st, so this would bill all residents in advance
            throw new BusinessRuleException("FUTURE_BILLING_MONTH", "Rent for " + month + " cannot be generated before the month starts");
        }
        List<Long> allocationIds = allocationRepository.findUnbilledForMonth(month.atDay(1));
        int generated = 0;
        int skipped = 0;
        int failed = 0;
        for (Long allocationId : allocationIds) {
            try {
                if (invoiceService.generateRentInvoice(allocationId, month)) {
                    generated++;
                } else {
                    skipped++;
                }
            } catch (DataIntegrityViolationException e) {
                if (ConstraintViolationTranslator.isConstraint(e, "uk_invoices_rent_month")) {
                    skipped++;
                } else {
                    failed++;
                    log.error("Rent invoice generation failed for allocation {}", allocationId, e);
                }
            } catch (RuntimeException e) {
                failed++;
                log.error("Rent invoice generation failed for allocation {}", allocationId, e);
            }
        }
        log.info("Rent invoice generation for {}: candidates={} generated={} skipped={} failed={}",
                month, allocationIds.size(), generated, skipped, failed);
        return new BillingDtos.GenerationResult(month.atDay(1), generated, skipped, failed);
    }

    @Scheduled(cron = "${app.jobs.overdue-cron:0 0 1 * * *}", zone = "${app.business-timezone}")
    @SchedulerLock(name = "invoiceOverdueMarking", lockAtMostFor = "PT15M", lockAtLeastFor = "PT1M")
    public void markOverdueInvoices() {
        LocalDate today = LocalDate.now(clock);
        int marked = 0;
        for (Long id : invoiceRepository.findIdsBecomingOverdue(today)) {
            try {
                if (invoiceService.markOverdue(id, today)) {
                    marked++;
                }
            } catch (RuntimeException e) {
                log.error("Failed to mark invoice {} overdue", id, e);
            }
        }
        log.info("Marked {} invoices overdue", marked);
    }

    /** "Rent due in N days" reminders; the notification dedup key prevents duplicates if this runs twice. */
    @Scheduled(cron = "${app.jobs.due-reminder-cron:0 0 9 * * *}", zone = "${app.business-timezone}")
    @SchedulerLock(name = "paymentDueReminders", lockAtMostFor = "PT15M", lockAtLeastFor = "PT1M")
    @Transactional
    public void sendDueReminders() {
        LocalDate dueDate = LocalDate.now(clock).plusDays(config.dueReminderDaysBefore());
        List<Invoice> invoices = invoiceRepository.findOpenDueOn(dueDate);
        invoices.forEach(i -> events.publishEvent(new BillingEvents.PaymentDue(i.getId(), i.getInvoiceNumber(),
                i.getOrganizationId(), i.getTenantId(), i.balance(), i.getDueDate(), false)));
        log.info("Queued {} payment-due reminders for {}", invoices.size(), dueDate);
    }
}
