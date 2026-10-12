package com.pgms.notification;

import com.pgms.auth.AuthEvents;
import com.pgms.billing.BillingEvents;
import com.pgms.billing.InvoiceType;
import com.pgms.booking.BookingEvents;
import com.pgms.booking.BookingStatus;
import com.pgms.common.domain.Priority;
import com.pgms.complaint.ComplaintActivityType;
import com.pgms.complaint.ComplaintEvents;
import com.pgms.complaint.ComplaintStatus;
import com.pgms.config.AppProperties;
import com.pgms.maintenance.MaintenanceEvents;
import com.pgms.maintenance.MaintenanceStatus;
import com.pgms.user.RoleName;
import com.pgms.user.User;
import com.pgms.user.UserRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Translates domain events into notifications. Listeners run synchronously inside the publisher's transaction,
 * so the notification rows commit (or roll back) together with the business change. Domain modules only publish
 * events and never depend on this module. Each message carries the variables of its fixed SMS template; which
 * channels are actually used is decided by {@link NotificationPolicy} and the recipients' preferences.
 */
@Component
public class NotificationEventHandler {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd MMM HH:mm");

    private final NotificationService notifications;
    private final UserRepository userRepository;
    private final ZoneId zone;

    public NotificationEventHandler(NotificationService notifications, UserRepository userRepository, AppProperties properties) {
        this.notifications = notifications;
        this.userRepository = userRepository;
        this.zone = properties.businessTimezone();
    }

    // ---- booking & occupancy ----

    @EventListener
    public void on(BookingEvents.BedReserved e) {
        String until = DATE_TIME.format(e.reservedUntil().atZone(zone));
        notifications.notifyTenant(e.tenantId(), NotificationMessage.of(NotificationType.BOOKING_CONFIRMED,
                "Bed reserved - " + e.reference(),
                "Your bed is reserved for check-in on " + DATE.format(e.expectedCheckInDate())
                        + ". The reservation is held until " + until + ".",
                "BOOKING", e.bookingId())
                .withParam("ref", e.reference()).withParam("date", DATE.format(e.expectedCheckInDate())).withParam("until", until));
    }

    @EventListener
    public void on(BookingEvents.ReservationReleased e) {
        String reason = e.outcome() == BookingStatus.EXPIRED ? "expired" : "was cancelled";
        notifications.notifyTenant(e.tenantId(), NotificationMessage.of(NotificationType.BOOKING_RELEASED,
                "Reservation " + e.reference() + " " + reason,
                "Your bed reservation " + e.reference() + " " + reason + ". Contact the property to book again.",
                "BOOKING", e.bookingId())
                .withParam("ref", e.reference())
                .withParam("outcome", e.outcome() == BookingStatus.EXPIRED ? "expired" : "been cancelled"));
    }

    @EventListener
    public void on(BookingEvents.TenantCheckedIn e) {
        notifications.notifyTenant(e.tenantId(), NotificationMessage.of(NotificationType.MOVE_IN, "Welcome!",
                "You have been checked in on " + DATE.format(e.checkInDate())
                        + ". Your deposit and first rent invoices are available in the portal.", "ALLOCATION", e.allocationId())
                .withParam("date", DATE.format(e.checkInDate())));
        notifications.notifyUsers(managers(e.propertyId()), NotificationMessage.of(NotificationType.MOVE_IN,
                "Tenant checked in", "Tenant #" + e.tenantId() + " checked into bed #" + e.bedId() + ".",
                "ALLOCATION", e.allocationId()));
    }

    @EventListener
    public void on(BookingEvents.TenantCheckedOut e) {
        notifications.notifyTenant(e.tenantId(), NotificationMessage.of(NotificationType.MOVE_OUT, "Check-out complete",
                "You have been checked out on " + DATE.format(e.checkOutDate()) + ". Thank you for staying with us.",
                "ALLOCATION", e.allocationId())
                .withParam("date", DATE.format(e.checkOutDate())));
        notifications.notifyUsers(managers(e.propertyId()), NotificationMessage.of(NotificationType.MOVE_OUT,
                "Tenant checked out", "Tenant #" + e.tenantId() + " vacated bed #" + e.bedId() + ".",
                "ALLOCATION", e.allocationId()));
    }

    @EventListener
    public void on(BookingEvents.TenantTransferred e) {
        notifications.notifyTenant(e.tenantId(), NotificationMessage.of(NotificationType.ROOM_TRANSFER, "Room transfer",
                "You have been moved to a new bed effective " + DATE.format(e.transferDate()) + ".",
                "ALLOCATION", e.toAllocationId())
                .withParam("date", DATE.format(e.transferDate())));
    }

    // ---- billing ----

    @EventListener
    public void on(BillingEvents.InvoiceIssued e) {
        notifications.notifyTenant(e.tenantId(), NotificationMessage.of(NotificationType.INVOICE_ISSUED,
                "New invoice " + e.invoiceNumber(),
                "Invoice " + e.invoiceNumber() + " for INR " + e.amount() + " is due on " + DATE.format(e.dueDate()) + ".",
                "INVOICE", e.invoiceId())
                .withParam("inv", e.invoiceNumber()).withParam("amt", amount(e.amount()))
                .withParam("date", DATE.format(e.dueDate()))
                // the matrix sends SMS for rent invoices only
                .important(e.type() == InvoiceType.RENT));
    }

    @EventListener
    public void on(BillingEvents.PaymentDue e) {
        NotificationType type = e.overdue() ? NotificationType.PAYMENT_OVERDUE : NotificationType.PAYMENT_DUE;
        String title = e.overdue() ? "Payment overdue - " + e.invoiceNumber() : "Payment due soon - " + e.invoiceNumber();
        String body = e.overdue()
                ? "INR " + e.balance() + " on invoice " + e.invoiceNumber() + " was due on " + DATE.format(e.dueDate()) + "."
                : "INR " + e.balance() + " on invoice " + e.invoiceNumber() + " is due on " + DATE.format(e.dueDate()) + ".";
        notifications.notifyTenant(e.tenantId(), NotificationMessage.of(type, title, body, "INVOICE", e.invoiceId())
                .withDedupKey(type.name() + ":" + e.invoiceId() + ":" + e.dueDate())
                .withParam("inv", e.invoiceNumber()).withParam("amt", amount(e.balance()))
                .withParam("date", DATE.format(e.dueDate())));
    }

    @EventListener
    public void on(BillingEvents.PaymentReceived e) {
        notifications.notifyTenant(e.tenantId(), NotificationMessage.of(NotificationType.PAYMENT_RECEIVED,
                "Payment received - " + e.receiptNumber(),
                "We received INR " + e.amount() + " against invoice " + e.invoiceNumber() + ". Remaining balance: INR "
                        + e.remainingBalance() + ".", "PAYMENT", e.paymentId())
                .withParam("amt", amount(e.amount())).withParam("inv", e.invoiceNumber())
                .withParam("rcpt", e.receiptNumber()).withParam("bal", amount(e.remainingBalance())));
    }

    @EventListener
    public void on(BillingEvents.RefundIssued e) {
        notifications.notifyTenant(e.tenantId(), NotificationMessage.of(NotificationType.REFUND_ISSUED,
                "Refund issued - " + e.refundReference(), "A refund of INR " + e.amount() + " has been issued.",
                "REFUND", e.refundId())
                .withParam("amt", amount(e.amount())).withParam("ref", e.refundReference()));
    }

    // ---- maintenance ----

    @EventListener
    public void on(MaintenanceEvents.StatusChanged e) {
        if (e.from() == null) {
            notifications.notifyUsers(managers(e.propertyId()), NotificationMessage.of(NotificationType.MAINTENANCE_CREATED,
                    "Maintenance " + e.ticketNumber() + ": " + e.to(), "New " + priority(e.priority()) + "priority ticket "
                            + e.ticketNumber() + " is " + e.to() + ".", "MAINTENANCE", e.requestId())
                    .withParam("no", e.ticketNumber()).withParam("status", e.to()));
            return;
        }
        NotificationMessage message = NotificationMessage.of(NotificationType.MAINTENANCE_UPDATE,
                "Maintenance " + e.ticketNumber() + ": " + e.to(), "Ticket " + e.ticketNumber() + " is now " + e.to() + ".",
                "MAINTENANCE", e.requestId())
                .withParam("no", e.ticketNumber()).withParam("status", e.to())
                // email to the resident/raiser by default once the ticket is resolved
                .important(e.to() == MaintenanceStatus.RESOLVED);
        if (e.tenantId() != null) {
            notifications.notifyTenant(e.tenantId(), message);
        } else {
            notifications.notifyUser(e.raisedByUserId(), message);
        }
        if (e.to() == MaintenanceStatus.ASSIGNED && e.assigneeUserId() != null) {
            notifications.notifyUser(e.assigneeUserId(), NotificationMessage.of(NotificationType.MAINTENANCE_ASSIGNED,
                    "New ticket assigned: " + e.ticketNumber(), "You have been assigned " + priority(e.priority())
                            + "priority maintenance ticket " + e.ticketNumber() + ".", "MAINTENANCE", e.requestId())
                    .withParam("no", e.ticketNumber()).withParam("priority", e.priority())
                    .important(e.priority() == Priority.HIGH || e.priority() == Priority.CRITICAL));
        }
    }

    @EventListener
    public void on(MaintenanceEvents.SlaBreached e) {
        boolean critical = e.priority() == Priority.CRITICAL;
        notifications.notifyUsers(managersAndAdmins(e.organizationId(), e.propertyId()), NotificationMessage.of(
                NotificationType.MAINTENANCE_SLA_BREACH, "SLA breached: " + e.ticketNumber(),
                e.priority() + " priority ticket " + e.ticketNumber() + " has missed its resolution target.",
                "MAINTENANCE", e.requestId()).withDedupKey("SLA_BREACH:" + e.requestId())
                .withParam("no", e.ticketNumber()).withParam("priority", e.priority())
                // SMS only for CRITICAL tickets, and those do not wait for quiet hours to end
                .important(critical).urgent(critical));
    }

    // ---- complaints ----

    @EventListener
    public void on(ComplaintEvents.ComplaintUpdated e) {
        NotificationMessage message = NotificationMessage.of(NotificationType.COMPLAINT_UPDATE,
                "Complaint " + e.complaintNumber() + " updated", "Complaint " + e.complaintNumber() + " is " + e.status()
                        + " (" + e.activity().name().toLowerCase().replace('_', ' ') + ").", "COMPLAINT", e.complaintId())
                .withParam("no", e.complaintNumber()).withParam("status", e.status())
                .important(e.status() == ComplaintStatus.RESOLVED);
        if (e.visibleToTenant()) {
            notifications.notifyUser(e.raisedByUserId(), message);
        }
        if (e.assigneeUserId() != null && !e.assigneeUserId().equals(e.raisedByUserId())) {
            notifications.notifyUser(e.assigneeUserId(), message);
        }
        if (e.activity() == ComplaintActivityType.CREATED) {
            // a new complaint has no assignee yet: without this nobody on the operator side would hear about it
            notifications.notifyUsers(managers(e.propertyId()).stream()
                    .filter(u -> !u.getId().equals(e.raisedByUserId())).toList(), message);
        }
    }

    @EventListener
    public void on(ComplaintEvents.ComplaintEscalated e) {
        notifications.notifyUsers(managersAndAdmins(e.organizationId(), e.propertyId()), NotificationMessage.of(
                NotificationType.COMPLAINT_ESCALATED, "Complaint escalated: " + e.complaintNumber(),
                "Complaint " + e.complaintNumber() + " escalated to level " + e.escalationLevel()
                        + (e.automatic() ? " (deadline missed)." : "."), "COMPLAINT", e.complaintId())
                .withDedupKey("ESCALATED:" + e.complaintId() + ":" + e.escalationLevel())
                .withParam("no", e.complaintNumber()).withParam("level", e.escalationLevel())
                .important(e.escalationLevel() >= 2));
    }

    // ---- account security (mandatory, bypass preferences) ----

    @EventListener
    public void on(AuthEvents.OtpIssued e) {
        String validity = humanize(e.validity());
        NotificationMessage message = e.purpose() == AuthEvents.OtpPurpose.SETUP
                ? NotificationMessage.of(NotificationType.PORTAL_ACCOUNT_CREATED, "Your tenant portal account is ready",
                        "An account has been created for you on the tenant portal. To choose your password, open the portal, "
                                + "go to \"Set password\" and enter the code below together with your email address or phone number.",
                        "USER", e.userId())
                : NotificationMessage.of(NotificationType.PASSWORD_RESET_OTP, "Your password reset code",
                        "We received a request to reset your password. Enter the code below to choose a new password. "
                                + "If you did not ask for this, you can ignore this message.", "USER", e.userId());
        message = message.withParam("code", e.code()).withParam("validity", validity).urgent(true);
        message = switch (e.delivery()) {
            case EMAIL -> message.onlyVia(NotificationChannel.EMAIL);
            case SMS -> message.onlyVia(NotificationChannel.SMS);
            case EMAIL_AND_SMS -> message.onlyVia(NotificationChannel.EMAIL, NotificationChannel.SMS);
        };
        notifications.notifyUser(e.userId(), message);
    }

    @EventListener
    public void on(AuthEvents.PasswordChanged e) {
        String time = DATE_TIME.format(e.at().atZone(zone));
        notifications.notifyUser(e.userId(), NotificationMessage.of(NotificationType.PASSWORD_CHANGED,
                "Your password was changed",
                "The password of your account was changed on " + time + " and all devices were signed out. "
                        + "If this was not you, contact the PG office immediately.", "USER", e.userId())
                .withParam("time", time));
    }

    private List<User> managers(Long propertyId) {
        return userRepository.findActiveAssignedToProperty(propertyId, Set.of(RoleName.MANAGER));
    }

    private List<User> managersAndAdmins(Long organizationId, Long propertyId) {
        return Stream.concat(managers(propertyId).stream(),
                        userRepository.findActiveByOrganizationAndRoles(organizationId, Set.of(RoleName.PROPERTY_ADMIN)).stream())
                .distinct().toList();
    }

    private static String amount(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static String priority(Priority priority) {
        return priority == null ? "" : priority.name() + " ";
    }

    private static String humanize(Duration duration) {
        if (duration.toDays() >= 1 && duration.toHours() % 24 == 0) {
            return duration.toDays() + (duration.toDays() == 1 ? " day" : " days");
        }
        if (duration.toHours() >= 1 && duration.toMinutes() % 60 == 0) {
            return duration.toHours() + (duration.toHours() == 1 ? " hour" : " hours");
        }
        return duration.toMinutes() + " min";
    }
}
