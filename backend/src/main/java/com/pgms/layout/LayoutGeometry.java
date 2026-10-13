package com.pgms.layout;

import com.pgms.common.exception.BadRequestException;
import com.pgms.common.exception.BusinessRuleException;
import com.pgms.room.Room;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Pure geometry rules of the floor layout editor. Spaces are axis-aligned rectangles on an integer grid; a rectangle
 * covers the half-open cells {@code [x, x + w) x [y, y + h)}, so spaces may share an edge but not a cell.
 */
public final class LayoutGeometry {

    private LayoutGeometry() {
    }

    /** A placed space; {@code name} is only used in error messages. */
    public record Rect(Long id, String name, int x, int y, int w, int h) {

        public boolean overlaps(Rect other) {
            return x < other.x + other.w && other.x < x + w && y < other.y + other.h && other.y < y + h;
        }

        public boolean fitsIn(int cols, int rows) {
            return x >= 0 && y >= 0 && x + w <= cols && y + h <= rows;
        }
    }

    /**
     * Turns raw request values into a rectangle: all four {@code null} means "not placed" (empty result), any other mix
     * of {@code null}s, a negative origin or an empty size is a malformed request (400).
     */
    public static Optional<Rect> toRect(Long id, String name, Integer x, Integer y, Integer w, Integer h) {
        if (x == null && y == null && w == null && h == null) {
            return Optional.empty();
        }
        if (x == null || y == null || w == null || h == null) {
            throw new BadRequestException("INVALID_GEOMETRY",
                    "layoutX, layoutY, layoutW and layoutH must be given together (or all omitted to unplace " + name + ")");
        }
        if (x < 0 || y < 0 || w < 1 || h < 1) {
            throw new BadRequestException("INVALID_GEOMETRY",
                    "Position of " + name + " must be non-negative and its width/height at least 1");
        }
        return Optional.of(new Rect(id, name, x, y, w, h));
    }

    /** Current placement of a space, if it is drawn on the floor. */
    public static Optional<Rect> rectOf(Room room) {
        if (!room.isPlaced()) {
            return Optional.empty();
        }
        return Optional.of(new Rect(room.getId(), nameOf(room.getRoomNumber()), room.getLayoutX(), room.getLayoutY(),
                room.getLayoutW(), room.getLayoutH()));
    }

    public static String nameOf(String roomNumber) {
        return "space " + roomNumber;
    }

    /** Every rectangle must lie inside a {@code cols x rows} grid (422 LAYOUT_OUT_OF_BOUNDS otherwise). */
    public static void requireInsideGrid(int cols, int rows, Collection<Rect> rects) {
        for (Rect r : rects) {
            if (!r.fitsIn(cols, rows)) {
                throw new BusinessRuleException("LAYOUT_OUT_OF_BOUNDS", r.name() + " at (" + r.x() + "," + r.y() + ") size "
                        + r.w() + "x" + r.h() + " does not fit the " + cols + "x" + rows + " floor grid");
            }
        }
    }

    /**
     * No two rectangles may share a cell (422 LAYOUT_OVERLAP naming the first offending pair). Sorted by x, the inner
     * loop stops at the first rectangle starting right of the current one, so typical floor plans are near-linear.
     */
    public static void requireNoOverlap(Collection<Rect> rects) {
        List<Rect> sorted = new ArrayList<>(rects);
        sorted.sort(Comparator.comparingInt(Rect::x).thenComparingInt(Rect::y));
        for (int i = 0; i < sorted.size(); i++) {
            Rect a = sorted.get(i);
            for (int j = i + 1; j < sorted.size() && sorted.get(j).x() < a.x() + a.w(); j++) {
                Rect b = sorted.get(j);
                if (a.overlaps(b)) {
                    throw new BusinessRuleException("LAYOUT_OVERLAP", a.name() + " overlaps " + b.name());
                }
            }
        }
    }

    public static void validate(int cols, int rows, Collection<Rect> rects) {
        requireInsideGrid(cols, rows, rects);
        requireNoOverlap(rects);
    }
}
