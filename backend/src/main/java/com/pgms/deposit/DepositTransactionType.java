package com.pgms.deposit;

/** Ledger entry kinds. Money collected is not stored here: it comes from the payments on the stay's deposit invoices. */
public enum DepositTransactionType {
    /** Deposit (or rent credit) applied to open rent/other dues at settlement. */
    DUES_ADJUSTED,
    /** Deposit applied to a deduction (settlement invoice: notice shortfall, damages, cleaning, ...). */
    DEDUCTION,
    /** Remainder kept by the owner, posted as a deduction (revenue). */
    FORFEIT,
    /** Paid out to the resident. */
    REFUND,
    /** Informational: a top-up deposit invoice was issued. */
    TOP_UP_REQUESTED,
    /** Rent paid for days not stayed, owed back to the resident together with the deposit. */
    RENT_CREDIT
}
