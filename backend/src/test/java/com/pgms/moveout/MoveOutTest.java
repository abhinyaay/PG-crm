package com.pgms.moveout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pgms.common.exception.ConflictException;
import com.pgms.moveout.MoveOutCalculator.NoticeTerms;
import com.pgms.moveout.MoveOutEnums.Initiator;
import com.pgms.moveout.MoveOutEnums.ItemCondition;
import com.pgms.moveout.MoveOutEnums.Reason;
import com.pgms.moveout.MoveOutEnums.Status;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class MoveOutTest {

    private static final LocalDate NOTICE = LocalDate.of(2026, 10, 1);
    private static final Instant NOW = Instant.parse("2026-10-01T06:00:00Z");

    private static MoveOut moveOut() {
        MoveOut m = new MoveOut("MO-1", 1L, 2L, 3L, 4L, 5L, Initiator.TENANT, NOTICE, NOTICE.plusDays(10), Reason.JOB_CHANGE,
                null, "me@upi");
        m.applyTerms(new NoticeTerms(30, NOTICE.plusDays(30), 20, new BigDecimal("6000.00"), null, new BigDecimal("0.00")));
        return m;
    }

    private static MoveOut.Settlement settlement() {
        BigDecimal z = BigDecimal.ZERO;
        return new MoveOut.Settlement(NOTICE.plusDays(10), 9L, 8L, new BigDecimal("18000"), z, z, z, z,
                new BigDecimal("18000"), z, NOTICE.plusDays(17));
    }

    @Test
    void happyPathNoticeInspectSettleClose() {
        MoveOut m = moveOut();
        assertThat(m.getStatus()).isEqualTo(Status.NOTICE_GIVEN);
        assertThat(m.effectiveShortfall()).isEqualByComparingTo("6000");
        m.recordInspection(List.of(new MoveOut.Item("Chair", ItemCondition.DAMAGED, new BigDecimal("500"), "leg broken")),
                "ok", 7L, NOW);
        assertThat(m.getStatus()).isEqualTo(Status.INSPECTED);
        assertThat(m.getItems()).singleElement().satisfies(i -> assertThat(i.getCondition()).isEqualTo(ItemCondition.DAMAGED));
        assertThatThrownBy(() -> m.withdraw(NOW)).isInstanceOf(ConflictException.class);
        m.settle(settlement(), NOW);
        assertThat(m.getStatus()).isEqualTo(Status.SETTLED);
        assertThat(m.getRefundDue()).isEqualByComparingTo("18000");
        assertThatThrownBy(() -> m.settle(settlement(), NOW)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("MO-1");
        assertThatThrownBy(() -> m.recordInspection(List.of(), null, 7L, NOW)).isInstanceOf(ConflictException.class);
        m.close("done", NOW);
        assertThat(m.getStatus()).isEqualTo(Status.CLOSED);
        assertThatThrownBy(() -> m.close("again", NOW)).isInstanceOf(ConflictException.class);
    }

    @Test
    void withdrawOnlyBeforeInspectionAndWaiverZeroesCharges() {
        MoveOut m = moveOut();
        m.reschedule(NOTICE.plusDays(40), new NoticeTerms(30, NOTICE.plusDays(30), 0, BigDecimal.ZERO, null, BigDecimal.ZERO),
                false);
        assertThat(m.getShortfallDays()).isZero();
        m.waiveCharges();
        assertThat(m.effectiveShortfall()).isEqualByComparingTo("0");
        assertThat(m.effectiveLockInPenalty()).isEqualByComparingTo("0");
        m.followAllocation(11L, 12L);
        assertThat(m.getAllocationId()).isEqualTo(11L);
        m.withdraw(NOW);
        assertThat(m.getStatus()).isEqualTo(Status.WITHDRAWN);
        assertThat(m.getStatus().isOpen()).isFalse();
        assertThatThrownBy(() -> m.reschedule(NOTICE, null, false)).isInstanceOf(ConflictException.class);
    }
}
