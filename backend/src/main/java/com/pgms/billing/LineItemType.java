package com.pgms.billing;

public enum LineItemType {
    RENT,
    SECURITY_DEPOSIT,
    ELECTRICITY,
    FOOD,
    LAUNDRY,
    LATE_FEE,
    OTHER,
    DISCOUNT,
    PARKING,
    INTERNET,
    /** Deduction from the deposit (cleaning, lock-in penalty, forfeiture, ...). */
    DEDUCTION,
    NOTICE_SHORTFALL,
    DAMAGE
}
