package com.pgms.notification;

import com.pgms.deposit.DepositEvents;
import com.pgms.moveout.MoveOutEnums.Initiator;
import com.pgms.moveout.MoveOutEvents;
import com.pgms.user.RoleName;
import com.pgms.user.User;
import com.pgms.user.UserRepository;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Notifications of the move-out and deposit modules (kept apart from {@link NotificationEventHandler} so the
 * feature stays self-contained). Same contract: synchronous, inside the publisher's transaction.
 */
@Component
public class MoveOutNotificationHandler {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");

    private final NotificationService notifications;
    private final UserRepository userRepository;

    public MoveOutNotificationHandler(NotificationService notifications, UserRepository userRepository) {
        this.notifications = notifications;
        this.userRepository = userRepository;
    }

    @EventListener
    public void on(MoveOutEvents.MoveOutRequested e) {
        String date = DATE.format(e.expectedMoveOutDate());
        String shortfall = e.shortfallDays() > 0 && e.shortfallCharge().signum() > 0
                ? " Notice shortfall: " + e.shortfallDays() + " days (INR " + amount(e.shortfallCharge()) + ")." : "";
        notifications.notifyTenant(e.tenantId(), NotificationMessage.of(NotificationType.MOVE_OUT_REQUESTED,
                "Notice received - " + e.reference(), "Your notice to move out on " + date + " has been recorded." + shortfall
                        + " You can withdraw it until the inspection.", "MOVE_OUT", e.moveOutId())
                .withParam("date", date).withParam("ref", e.reference())
                .withParam("days", e.shortfallDays()).withParam("amt", amount(e.shortfallCharge())));
        notifications.notifyUsers(managers(e.propertyId()), NotificationMessage.of(NotificationType.MOVE_OUT_REQUESTED,
                "Notice: " + e.tenantName() + " leaves on " + date, e.tenantName() + " gave notice"
                        + (e.initiatedBy() == Initiator.TENANT ? " from the portal" : "") + " to move out on " + date
                        + ". Schedule the inspection.", "MOVE_OUT", e.moveOutId()));
    }

    @EventListener
    public void on(MoveOutEvents.MoveOutWithdrawn e) {
        notifications.notifyUsers(managers(e.propertyId()), NotificationMessage.of(NotificationType.MOVE_OUT_WITHDRAWN,
                "Notice withdrawn - " + e.reference(), e.tenantName() + " withdrew the move-out notice.", "MOVE_OUT",
                e.moveOutId()));
    }

    @EventListener
    public void on(MoveOutEvents.SettlementFinalized e) {
        String outcome = e.refundDue().signum() > 0
                ? "refund INR " + amount(e.refundDue()) + (e.refundDueBy() == null ? "" : " by " + DATE.format(e.refundDueBy()))
                : e.payableByTenant().signum() > 0 ? "amount payable INR " + amount(e.payableByTenant()) : "nothing further due";
        notifications.notifyTenant(e.tenantId(), NotificationMessage.of(NotificationType.SETTLEMENT_FINALIZED,
                "Final settlement - " + e.reference(), "Deposit INR " + amount(e.depositHeld()) + ", deductions INR "
                        + amount(e.deductions()) + ": " + outcome + ".", "MOVE_OUT", e.moveOutId())
                .withDedupKey("SETTLEMENT_FINALIZED:" + e.moveOutId())
                .withParam("dep", amount(e.depositHeld())).withParam("ded", amount(e.deductions()))
                .withParam("outcome", outcome));
    }

    @EventListener
    public void on(DepositEvents.DepositRefunded e) {
        String reference = e.reference() == null ? "-" : e.reference();
        notifications.notifyTenant(e.tenantId(), NotificationMessage.of(NotificationType.DEPOSIT_REFUND_ISSUED,
                "Deposit refund sent", "Your deposit refund of INR " + amount(e.amount()) + " was sent via " + e.method()
                        + " (reference " + reference + ")."
                        + (e.refundDueAfter().signum() > 0 ? " INR " + amount(e.refundDueAfter()) + " is still due." : ""),
                "DEPOSIT", e.depositAccountId())
                .withDedupKey("DEPOSIT_REFUND_ISSUED:" + e.transactionId())
                .withParam("amt", amount(e.amount())).withParam("method", e.method().name().replace('_', ' '))
                .withParam("ref", reference));
    }

    @EventListener
    public void on(DepositEvents.DepositRefundOverdue e) {
        notifications.notifyUsers(userRepository.findActiveByOrganizationAndRoles(e.organizationId(),
                Set.of(RoleName.PROPERTY_ADMIN)), NotificationMessage.of(NotificationType.DEPOSIT_REFUND_OVERDUE,
                "Deposit refund overdue: " + e.tenantName(), "Deposit refund of INR " + amount(e.refundDue()) + " for "
                        + e.tenantName() + " was due on " + DATE.format(e.refundDueBy()) + ".", "DEPOSIT", e.depositAccountId())
                .withDedupKey("DEPOSIT_REFUND_OVERDUE:" + e.depositAccountId() + ":" + e.today())
                .withParam("amt", amount(e.refundDue())).withParam("date", DATE.format(e.refundDueBy())));
    }

    private List<User> managers(Long propertyId) {
        return userRepository.findActiveAssignedToProperty(propertyId, Set.of(RoleName.MANAGER));
    }

    private static String amount(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }
}
