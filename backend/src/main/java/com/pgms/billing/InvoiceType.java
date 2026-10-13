package com.pgms.billing;

public enum InvoiceType {
    RENT,
    SECURITY_DEPOSIT,
    /** Metered utilities (electricity), billed separately from rent. */
    UTILITY,
    /** Move-out and deposit charges: notice shortfall, lock-in penalty, damages, deductions (revenue). */
    SETTLEMENT
}
