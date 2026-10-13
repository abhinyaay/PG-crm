package com.pgms.room;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Generated bed codes: A, B, ... Z, AA, AB, ... (spreadsheet-column style), skipping codes already used in the room. */
final class BedCodes {

    private BedCodes() {
    }

    static List<String> next(Collection<String> taken, int count) {
        Set<String> used = new HashSet<>(taken);
        List<String> codes = new ArrayList<>(count);
        for (int index = 0; codes.size() < count; index++) {
            String code = code(index);
            if (used.add(code)) {
                codes.add(code);
            }
        }
        return codes;
    }

    /** 0 -> A, 25 -> Z, 26 -> AA, 27 -> AB ... */
    static String code(int index) {
        StringBuilder code = new StringBuilder();
        for (int n = index + 1; n > 0; n = (n - 1) / 26) {
            code.insert(0, (char) ('A' + (n - 1) % 26));
        }
        return code.toString();
    }
}
