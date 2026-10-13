package com.pgms.deposit;

import java.time.Clock;
import java.time.LocalDate;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily deposit work: overdue refund alerts to owners and the (idempotent) account backfill. */
@Component
public class DepositJobs {

    private static final Logger log = LoggerFactory.getLogger(DepositJobs.class);

    private final DepositAccountRepository accountRepository;
    private final DepositOperations operations;
    private final DepositAccountBackfill backfill;
    private final Clock clock;

    public DepositJobs(DepositAccountRepository accountRepository, DepositOperations operations,
                       DepositAccountBackfill backfill, Clock clock) {
        this.accountRepository = accountRepository;
        this.operations = operations;
        this.backfill = backfill;
        this.clock = clock;
    }

    /** Refunds past their due-by date: one DEPOSIT_REFUND_OVERDUE alert per account per day. */
    @Scheduled(cron = "${app.moveout.refund-overdue-cron:0 30 9 * * *}", zone = "${app.business-timezone}")
    @SchedulerLock(name = "depositRefundOverdue", lockAtMostFor = "PT15M", lockAtLeastFor = "PT1M")
    public int reportOverdueRefunds() {
        LocalDate today = LocalDate.now(clock);
        int reported = 0;
        for (Long id : accountRepository.findIdsWithOverdueRefund(today)) {
            try {
                if (operations.reportOverdueRefund(id, today)) {
                    reported++;
                }
            } catch (RuntimeException e) {
                log.error("Overdue refund check failed for deposit account {}", id, e);
            }
        }
        log.info("Reported {} overdue deposit refunds", reported);
        return reported;
    }

    @Scheduled(cron = "${app.moveout.deposit-backfill-cron:0 45 2 * * *}", zone = "${app.business-timezone}")
    @SchedulerLock(name = "depositAccountBackfill", lockAtMostFor = "PT15M", lockAtLeastFor = "PT1M")
    public void backfillAccounts() {
        backfill.run();
    }
}
