package com.pgms.room;

/** Sharing type. Fixed-sharing types imply an exact capacity; dormitories hold 5-20 beds. */
public enum RoomType {
    SINGLE(1, 1),
    DOUBLE(2, 2),
    TRIPLE(3, 3),
    QUAD(4, 4),
    DORMITORY(5, 20);

    private final int minCapacity;
    private final int maxCapacity;

    RoomType(int minCapacity, int maxCapacity) {
        this.minCapacity = minCapacity;
        this.maxCapacity = maxCapacity;
    }

    public boolean allowsCapacity(int capacity) {
        return capacity >= minCapacity && capacity <= maxCapacity;
    }

    /** The sharing type matching a bed count, used when beds are added to / removed from a ROOM one at a time. */
    public static RoomType forCapacity(int capacity) {
        for (RoomType type : values()) {
            if (type.allowsCapacity(capacity)) {
                return type;
            }
        }
        throw new IllegalArgumentException("No room type holds " + capacity + " beds");
    }
}
