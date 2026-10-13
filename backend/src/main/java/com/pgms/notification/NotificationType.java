package com.pgms.notification;

/**
 * What a notification is about. Stored as text ({@code notifications.type} has no CHECK constraint), so adding a value
 * needs no migration. Default channels per type and audience live in {@link NotificationPolicy}.
 */
public enum NotificationType {
    BOOKING_CONFIRMED,
    BOOKING_RELEASED,
    MOVE_IN,
    MOVE_OUT,
    ROOM_TRANSFER,
    INVOICE_ISSUED,
    PAYMENT_DUE,
    PAYMENT_OVERDUE,
    PAYMENT_RECEIVED,
    REFUND_ISSUED,
    /** New ticket, to the property's managers. */
    MAINTENANCE_CREATED,
    /** Ticket assigned, to the assignee. */
    MAINTENANCE_ASSIGNED,
    /** Status change, to the resident / raiser. */
    MAINTENANCE_UPDATE,
    MAINTENANCE_SLA_BREACH,
    COMPLAINT_UPDATE,
    COMPLAINT_ESCALATED,
    PORTAL_ACCOUNT_CREATED,
    PASSWORD_RESET_OTP,
    PASSWORD_CHANGED,
    /** A resident tried to enter after using up the daily entry limit: property managers + the resident (in-app). */
    ACCESS_LIMIT_EXCEEDED,
    /** A biometric gate device stopped calling in, to the property's managers. */
    ACCESS_DEVICE_OFFLINE,
    /** Late fee charged on an invoice, to the resident. */
    LATE_FEE_APPLIED,
    LATE_FEE_WAIVED,
    /** Daily per-property overdue summary, to managers (admins weekly). */
    OVERDUE_DIGEST,
    /** A resident reported an outside payment, to the property's managers. */
    PAYMENT_CLAIM_SUBMITTED,
    PAYMENT_CLAIM_REJECTED,
    /** Claims waiting longer than the SLA, to managers and admins. */
    PAYMENT_CLAIMS_PENDING,
    AUTOPAY_MANDATE_UPDATED,
    AUTOPAY_DEBIT_SUCCEEDED,
    AUTOPAY_DEBIT_FAILED,
    /** Notice given (acknowledgement to the resident, heads-up to managers). */
    MOVE_OUT_REQUESTED,
    MOVE_OUT_WITHDRAWN,
    /** Final move-out settlement statement, to the resident. */
    SETTLEMENT_FINALIZED,
    /** Deposit refund paid out, to the resident. */
    DEPOSIT_REFUND_ISSUED,
    /** Deposit refund past its due-by date, to the owners (property admins). */
    DEPOSIT_REFUND_OVERDUE
}
