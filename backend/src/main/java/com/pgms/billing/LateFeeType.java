package com.pgms.billing;

/** How a property's late fee is computed once the grace period is over. */
public enum LateFeeType {
    /** A fixed amount, charged once per invoice. */
    FLAT,
    /** A percentage of the invoice's RENT lines (never of the balance, so it cannot compound), charged once. */
    PERCENT_OF_RENT,
    /** A fixed amount for every day late, accrued daily up to the cap. */
    PER_DAY
}
