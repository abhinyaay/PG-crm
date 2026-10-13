package com.pgms.deposit;

import com.pgms.billing.PaymentMethod;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Domain events of the deposit module (consumed by notifications and the move-out module). */
public final class DepositEvents {

    private DepositEvents() {
    }

    /** A deposit refund was paid out; {@code refundDueAfter} is what is still owed afterwards. */
    public record DepositRefunded(Long depositAccountId, Long transactionId, Long organizationId, Long propertyId,
                                  Long tenantId, BigDecimal amount, PaymentMethod method, String reference,
                                  BigDecimal refundDueAfter) {
    }

    /** A settled deposit whose refund is past its due-by date (reported once per day per account). */
    public record DepositRefundOverdue(Long depositAccountId, Long organizationId, Long propertyId, Long tenantId,
                                       String tenantName, BigDecimal refundDue, LocalDate refundDueBy, LocalDate today) {
    }
}
