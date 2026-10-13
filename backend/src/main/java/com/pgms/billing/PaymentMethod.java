package com.pgms.billing;

public enum PaymentMethod {
    CASH,
    UPI,
    CARD,
    BANK_TRANSFER,
    CHEQUE,
    /** Collected automatically against a recurring-payment mandate; never recorded by hand. */
    AUTOPAY,
    /** Dues paid out of the security deposit at move-out; never recorded by hand. */
    DEPOSIT_ADJUSTMENT;

    /** Methods an operator may record through {@code POST /payments}. */
    public boolean isManual() {
        return this != AUTOPAY && this != DEPOSIT_ADJUSTMENT;
    }
}
