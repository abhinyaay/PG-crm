package com.pgms.billing;

import com.pgms.billing.CollectionsDtos.JobRunResult;
import com.pgms.common.exception.ConstraintViolationTranslator;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily collections work: late fees (01:15, after overdue marking at 01:00), T-N due reminders, D+n overdue reminders,
 * the overdue digest for managers and the pending payment-claims digest. ShedLock keeps each job single-run across
 * replicas; every item runs in its own transaction and every notification is deduplicated, so re-runs are safe.
 * The {@code run*(date)} methods are the job bodies, also used by the manual trigger endpoint and the tests.
 */
@Component
public class CollectionsJobs {

    private static final Logger log = LoggerFactory.getLogger(CollectionsJobs.class);
    /** Policies allow T-0 .. T-15 due reminders. */
    private static final int MAX_DUE_REMINDER_DAYS = 15;

    private final LateFeeService lateFeeService;
    private final CollectionReminderService reminderService;
    private final PaymentClaimService claimService;
    private final BillingPolicyService policyService;
    private final LateFeeEntryRepository lateFeeEntryRepository;
    private final CollectionsInvoiceQueries invoiceQueries;
    private final PaymentClaimRepository claimRepository;
    private final CollectionsProperties properties;
    private final Clock clock;

    public CollectionsJobs(LateFeeService lateFeeService, CollectionReminderService reminderService,
                           PaymentClaimService claimService, BillingPolicyService policyService,
                           LateFeeEntryRepository lateFeeEntryRepository, CollectionsInvoiceQueries invoiceQueries,
                           PaymentClaimRepository claimRepository, CollectionsProperties properties, Clock clock) {
        this.lateFeeService = lateFeeService;
        this.reminderService = reminderService;
        this.claimService = claimService;
        this.policyService = policyService;
        this.lateFeeEntryRepository = lateFeeEntryRepository;
        this.invoiceQueries = invoiceQueries;
        this.claimRepository = claimRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.collections.jobs.late-fee-cron:0 15 1 * * *}", zone = "${app.business-timezone}")
    @SchedulerLock(name = "invoiceLateFees", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void applyLateFees() {
        runLateFees(LocalDate.now(clock));
    }

    public JobRunResult runLateFees(LocalDate today) {
        int applied = 0;
        int skipped = 0;
        int failed = 0;
        for (Long id : lateFeeEntryRepository.findLateFeeCandidates(today, policyService.lateFeesEnabledByDefault())) {
            try {
                if (lateFeeService.applyLateFee(id, today) == LateFeeService.Outcome.APPLIED) {
                    applied++;
                } else {
                    skipped++;
                }
            } catch (DataIntegrityViolationException e) {
                if (ConstraintViolationTranslator.isConstraint(e, "uk_late_fee_invoice_period")) {
                    skipped++;
                } else {
                    failed++;
                    log.error("Late fee failed for invoice {}", id, e);
                }
            } catch (RuntimeException e) {
                failed++;
                log.error("Late fee failed for invoice {}", id, e);
            }
        }
        log.info("Late fees for {}: applied={} skipped={} failed={}", today, applied, skipped, failed);
        return new JobRunResult(today, applied, skipped, failed);
    }

    /** T-N reminders with N from each property's policy (called by {@link BillingJobs#sendDueReminders}). */
    public int runDueReminders(LocalDate today) {
        List<Invoice> candidates = invoiceQueries.findOpenDueBetween(today, today.plusDays(MAX_DUE_REMINDER_DAYS));
        Map<Long, BillingPolicy> policies = policies(candidates);
        int sent = 0;
        for (Invoice invoice : candidates) {
            BillingPolicy policy = policies.get(invoice.getPropertyId());
            if (!ReminderSchedule.isDueReminderDay(policy.dueReminderDaysBefore(), invoice.getDueDate(), today)) {
                continue;
            }
            try {
                if (reminderService.sendDueReminder(invoice.getId())) {
                    sent++;
                }
            } catch (RuntimeException e) {
                log.error("Due reminder failed for invoice {}", invoice.getId(), e);
            }
        }
        log.info("Queued {} payment-due reminders for {}", sent, today);
        return sent;
    }

    @Scheduled(cron = "${app.collections.jobs.overdue-reminder-cron:0 15 9 * * *}", zone = "${app.business-timezone}")
    @SchedulerLock(name = "overdueReminders", lockAtMostFor = "PT15M", lockAtLeastFor = "PT1M")
    public void sendOverdueReminders() {
        runOverdueReminders(LocalDate.now(clock));
    }

    public int runOverdueReminders(LocalDate today) {
        List<Invoice> candidates = invoiceQueries.findOverdueDueBetween(today.minusDays(ReminderSchedule.MAX_OFFSET_DAY), today);
        Map<Long, BillingPolicy> policies = policies(candidates);
        int sent = 0;
        for (Invoice invoice : candidates) {
            BillingPolicy policy = policies.get(invoice.getPropertyId());
            if (!ReminderSchedule.isOverdueReminderDay(policy.overdueReminderOffsets(), invoice.getDueDate(), today)) {
                continue;
            }
            try {
                if (reminderService.sendOverdueReminder(invoice.getId(), today)) {
                    sent++;
                }
            } catch (RuntimeException e) {
                log.error("Overdue reminder failed for invoice {}", invoice.getId(), e);
            }
        }
        log.info("Queued {} overdue reminders for {}", sent, today);
        return sent;
    }

    @Scheduled(cron = "${app.collections.jobs.overdue-digest-cron:0 30 9 * * *}", zone = "${app.business-timezone}")
    @SchedulerLock(name = "overdueDigest", lockAtMostFor = "PT15M", lockAtLeastFor = "PT1M")
    public void sendOverdueDigest() {
        runOverdueDigest(LocalDate.now(clock));
    }

    public int runOverdueDigest(LocalDate today) {
        Map<Long, List<Long>> byProperty = invoiceQueries.findAllOverdue().stream().collect(Collectors.groupingBy(
                Invoice::getPropertyId, LinkedHashMap::new, Collectors.mapping(Invoice::getId, Collectors.toList())));
        int sent = 0;
        for (Map.Entry<Long, List<Long>> entry : byProperty.entrySet()) {
            try {
                if (reminderService.sendOverdueDigest(entry.getKey(), entry.getValue(), today)) {
                    sent++;
                }
            } catch (RuntimeException e) {
                log.error("Overdue digest failed for property {}", entry.getKey(), e);
            }
        }
        log.info("Queued overdue digests for {} properties", sent);
        return sent;
    }

    @Scheduled(cron = "${app.collections.jobs.claim-sla-cron:0 0 10 * * *}", zone = "${app.business-timezone}")
    @SchedulerLock(name = "paymentClaimSla", lockAtMostFor = "PT15M", lockAtLeastFor = "PT1M")
    public void sendPendingClaimsDigest() {
        runPendingClaimsDigest(LocalDate.now(clock));
    }

    public int runPendingClaimsDigest(LocalDate today) {
        Map<Long, List<Long>> byProperty = claimRepository
                .findPendingSubmittedBefore(clock.instant().minus(properties.claims().pendingSla())).stream()
                .collect(Collectors.groupingBy(PaymentClaim::getPropertyId, LinkedHashMap::new,
                        Collectors.mapping(PaymentClaim::getId, Collectors.toList())));
        int sent = 0;
        for (Map.Entry<Long, List<Long>> entry : byProperty.entrySet()) {
            try {
                if (claimService.sendPendingDigest(entry.getKey(), entry.getValue(), today)) {
                    sent++;
                }
            } catch (RuntimeException e) {
                log.error("Pending-claims digest failed for property {}", entry.getKey(), e);
            }
        }
        log.info("Queued pending payment-claim digests for {} properties", sent);
        return sent;
    }

    private Map<Long, BillingPolicy> policies(List<Invoice> invoices) {
        return policyService.effectiveFor(invoices.stream().map(Invoice::getPropertyId).collect(Collectors.toSet()));
    }
}
