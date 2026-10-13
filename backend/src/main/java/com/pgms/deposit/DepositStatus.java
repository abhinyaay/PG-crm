package com.pgms.deposit;

/**
 * Life cycle of one stay's security deposit. HELD while the stay runs (and for legacy stays never settled);
 * after the move-out settlement REFUND_DUE / PARTIALLY_REFUNDED until the refund is paid out (REFUNDED), SETTLED
 * when nothing was left to refund (all applied to dues), FORFEITED when the owner kept the remainder.
 */
public enum DepositStatus {
    HELD,
    REFUND_DUE,
    PARTIALLY_REFUNDED,
    REFUNDED,
    SETTLED,
    FORFEITED;

    public boolean refundPending() {
        return this == REFUND_DUE || this == PARTIALLY_REFUNDED;
    }
}
