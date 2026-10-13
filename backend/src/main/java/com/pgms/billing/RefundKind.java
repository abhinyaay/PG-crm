package com.pgms.billing;

/** CASH = money paid out to the resident; ADJUSTMENT = the paid amount was applied to other dues (no money moves). */
public enum RefundKind {
    CASH,
    ADJUSTMENT
}
