package com.pgms.moveout;

/** Small enumerations of the move-out module. */
public final class MoveOutEnums {

    private MoveOutEnums() {
    }

    /** NOTICE_GIVEN -> INSPECTED -> SETTLED -> CLOSED; NOTICE_GIVEN -> WITHDRAWN. */
    public enum Status {
        NOTICE_GIVEN,
        INSPECTED,
        SETTLED,
        CLOSED,
        WITHDRAWN;

        public boolean isOpen() {
            return this == NOTICE_GIVEN || this == INSPECTED;
        }
    }

    public enum Reason {
        JOB_CHANGE,
        RELOCATION,
        DISSATISFIED,
        PRICE,
        PERSONAL,
        EVICTION,
        OTHER
    }

    public enum Initiator {
        TENANT,
        STAFF
    }

    /** Condition of one checklist item; CLEANING / DAMAGED / MISSING usually carry a deduction. */
    public enum ItemCondition {
        OK,
        CLEANING,
        DAMAGED,
        MISSING
    }
}
