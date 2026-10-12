package com.pgms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pgms.room.BedRepository;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The bed row lock must cover the bed only. If it also locked the joined room row, every bed of a room would
 * serialise on that room and the "lock beds in ascending id order" rule would no longer prevent deadlocks
 * (two transfers inside one room could each hold a bed and wait for the room the other one holds).
 */
class BedLockingIT extends AbstractIntegrationTest {

    @Autowired
    private BedRepository bedRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void lockingABedLeavesItsRoomAndSiblingBedsLockable() throws Exception {
        Fixtures.Org org = fixtures.organization();
        Fixtures.PropertyLayout layout = fixtures.property(org, 1);
        long bedA = layout.bedIds().get(0);
        long bedB = layout.bedIds().get(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                bedRepository.findByIdForUpdate(bedA).orElseThrow();
                locked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Boolean siblingLocked = tx.execute(status -> {
                fixtures.jdbc().execute("SET LOCAL lock_timeout = '500ms'");
                boolean found = bedRepository.findByIdForUpdate(bedB).isPresent();
                // the room row itself must not be held by the other transaction either
                fixtures.jdbc().update("UPDATE rooms SET updated_at = now() WHERE id = ?", layout.roomIds().get(0));
                return found;
            });
            assertThat(siblingLocked).isTrue();

            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
