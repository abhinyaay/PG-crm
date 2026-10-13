package com.pgms.moveout;

import com.pgms.moveout.MoveOutEnums.Initiator;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Domain events of the move-out module (consumed by notifications). */
public final class MoveOutEvents {

    private MoveOutEvents() {
    }

    public record MoveOutRequested(Long moveOutId, String reference, Long organizationId, Long propertyId, Long tenantId,
                                   String tenantName, LocalDate expectedMoveOutDate, int shortfallDays,
                                   BigDecimal shortfallCharge, Initiator initiatedBy) {
    }

    public record MoveOutWithdrawn(Long moveOutId, String reference, Long organizationId, Long propertyId, Long tenantId,
                                   String tenantName) {
    }

    public record SettlementFinalized(Long moveOutId, String reference, Long organizationId, Long propertyId, Long tenantId,
                                      BigDecimal depositHeld, BigDecimal deductions, BigDecimal refundDue,
                                      LocalDate refundDueBy, BigDecimal payableByTenant) {
    }
}
