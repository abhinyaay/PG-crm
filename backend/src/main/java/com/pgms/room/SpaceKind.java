package com.pgms.room;

/**
 * What a space on a floor is used for. Only ROOM, HALL and DORMITORY hold beds; a ROOM follows the fixed sharing rules
 * of {@link RoomType}, halls and dormitories take up to {@link #MAX_OPEN_BEDS} individually added beds, and every other
 * kind (kitchen, washroom, ...) is drawn on the layout but has capacity 0.
 */
public enum SpaceKind {
    ROOM,
    HALL,
    DORMITORY,
    COMMON_AREA,
    KITCHEN,
    WASHROOM,
    LOUNGE,
    STUDY,
    STORE,
    OTHER;

    /** Upper bound for beds in a hall/dormitory space (ROOM spaces are bounded by their {@link RoomType}). */
    public static final int MAX_OPEN_BEDS = 50;

    /** Upper bound for beds in a ROOM space (the largest {@link RoomType}). */
    public static final int MAX_ROOM_BEDS = 20;

    public boolean holdsBeds() {
        return this == ROOM || this == HALL || this == DORMITORY;
    }

    /** Hall-like spaces: beds are added one by one and the sharing type is always DORMITORY. */
    public boolean isOpenPlan() {
        return this == HALL || this == DORMITORY;
    }

    public int maxBeds() {
        if (this == ROOM) {
            return MAX_ROOM_BEDS;
        }
        return isOpenPlan() ? MAX_OPEN_BEDS : 0;
    }
}
