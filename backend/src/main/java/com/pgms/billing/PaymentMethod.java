package com.pgms.billing;

public enum PaymentMethod {
    CASH,
    UPI,
    CARD,
    BANK_TRANSFER,
    CHEQUE,
    AUTOPAY,
    /** Internal: dues paid from the resident's security deposit. Never accepted from the payments API. */
    DEPOSIT_ADJUSTMENT
}
