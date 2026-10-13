package com.pgms.billing.autopay;

import com.pgms.billing.autopay.AutopayDtos.DebitRunResult;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily AutoPay run: expire debits the provider never confirmed, then request one debit per due invoice of every
 * resident with an ACTIVE mandate (one attempt per invoice per day, at most {@code max-attempts}; a failed attempt is
 * retried on the next run). ShedLock keeps it single-run across replicas.
 */
@Component
public class AutopayJobs {

    private static final Logger log = LoggerFactory.getLogger(AutopayJobs.class);

    private final PaymentMandateRepository mandateRepository;
    private final AutopayAttemptRepository attemptRepository;
    private final AutopayDebitService debitService;
    private final RecurringPaymentGateway gateway;
    private final AutopayProperties properties;
    private final Clock clock;

    public AutopayJobs(PaymentMandateRepository mandateRepository, AutopayAttemptRepository attemptRepository,
                       AutopayDebitService debitService, RecurringPaymentGateway gateway, AutopayProperties properties,
                       Clock clock) {
        this.mandateRepository = mandateRepository;
        this.attemptRepository = attemptRepository;
        this.debitService = debitService;
        this.gateway = gateway;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.payments.autopay.debit-cron:0 0 11 * * *}", zone = "${app.business-timezone}")
    @SchedulerLock(name = "autopayDebits", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void debitDueInvoices() {
        runDebits(LocalDate.now(clock));
    }

    public DebitRunResult runDebits(LocalDate today) {
        for (Long attemptId : attemptRepository.findUnconfirmedBefore(today.minusDays(properties.confirmationTimeoutDays()))) {
            try {
                debitService.expire(attemptId);
            } catch (RuntimeException e) {
                log.error("Could not expire AutoPay attempt {}", attemptId, e);
            }
        }
        int requested = 0;
        int succeeded = 0;
        int failed = 0;
        int pending = 0;
        int skipped = 0;
        for (Object[] row : mandateRepository.findDebitCandidates()) {
            Long invoiceId = (Long) row[0];
            Long mandateId = (Long) row[1];
            Optional<AutopayDebitService.PreparedDebit> prepared;
            try {
                prepared = debitService.prepare(invoiceId, mandateId, today);
            } catch (DataIntegrityViolationException e) {
                skipped++;
                continue;
            } catch (RuntimeException e) {
                log.error("AutoPay preparation failed for invoice {}", invoiceId, e);
                failed++;
                continue;
            }
            if (prepared.isEmpty()) {
                skipped++;
                continue;
            }
            requested++;
            AutopayDebitService.PreparedDebit debit = prepared.get();
            try {
                RecurringPaymentGateway.DebitResult result = gateway.requestDebit(debit.request());
                switch (debitService.recordOutcome(debit, result)) {
                    case SUCCEEDED -> succeeded++;
                    case FAILED -> failed++;
                    case PENDING -> pending++;
                    default -> log.warn("Unexpected AutoPay outcome for attempt {}", debit.attemptId());
                }
            } catch (RuntimeException e) {
                log.error("AutoPay debit request failed for attempt {}", debit.attemptId(), e);
                failed++;
                try {
                    debitService.recordError(debit, abbreviate("Gateway error: " + e.getMessage()));
                } catch (RuntimeException nested) {
                    log.error("Could not record the failure of AutoPay attempt {}", debit.attemptId(), nested);
                }
            }
        }
        log.info("AutoPay run {}: requested={} succeeded={} failed={} pending={} skipped={}", today, requested, succeeded,
                failed, pending, skipped);
        return new DebitRunResult(today, requested, succeeded, failed, pending, skipped);
    }

    private static String abbreviate(String message) {
        return message.length() > 255 ? message.substring(0, 255) : message;
    }
}
