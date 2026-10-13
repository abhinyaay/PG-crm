package com.pgms.access;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses the body of an ADMS {@code POST /iclock/cdata?table=ATTLOG} upload: one punch per line,
 * {@code PIN \t YYYY-MM-DD HH:MM:SS \t status \t verify \t workcode \t reserved...}. The device clock has no
 * time zone, so the local time is interpreted in the device's configured zone. A malformed line is skipped (and
 * counted) without affecting the rest of the upload.
 */
public final class AttLogParser {

    static final int MAX_PIN_LENGTH = 24;
    private static final Pattern LINE_BREAK = Pattern.compile("\\r?\\n|\\r");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern PIN = Pattern.compile("^[A-Za-z0-9]{1," + MAX_PIN_LENGTH + "}$");
    private static final DateTimeFormatter DEVICE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private AttLogParser() {
    }

    /** One valid punch. {@code status}/{@code verify} are null when the device did not send them. */
    public record Punch(String pin, LocalDateTime localTime, Instant occurredAt, Integer status, Integer verify) {
    }

    public record Result(List<Punch> punches, int skipped, List<String> errors) {
    }

    public static Result parse(String body, ZoneId deviceZone, int maxLines) {
        List<Punch> punches = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int skipped = 0;
        if (body == null || body.isBlank()) {
            return new Result(punches, 0, errors);
        }
        int lineNo = 0;
        for (String raw : LINE_BREAK.split(body)) {
            lineNo++;
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (punches.size() >= maxLines) {
                skipped++;
                continue;
            }
            try {
                punches.add(parseLine(line, deviceZone));
            } catch (IllegalArgumentException | DateTimeParseException e) {
                skipped++;
                if (errors.size() < 10) {
                    errors.add("line " + lineNo + ": " + e.getMessage());
                }
            }
        }
        return new Result(punches, skipped, errors);
    }

    static Punch parseLine(String line, ZoneId zone) {
        String[] fields = line.split("\t");
        String pin;
        String time;
        int next;
        if (fields.length >= 2) {
            pin = fields[0].strip();
            time = fields[1].strip();
            next = 2;
        } else {
            // some firmware/bridges separate with spaces: PIN DATE TIME STATUS VERIFY ...
            fields = WHITESPACE.split(line);
            if (fields.length < 3) {
                throw new IllegalArgumentException("expected PIN and timestamp");
            }
            pin = fields[0];
            time = fields[1] + " " + fields[2];
            next = 3;
        }
        if (!PIN.matcher(pin).matches()) {
            throw new IllegalArgumentException("invalid PIN");
        }
        LocalDateTime local = LocalDateTime.parse(time, DEVICE_TIME);
        Integer status = fields.length > next ? optionalInt(fields[next]) : null;
        Integer verify = fields.length > next + 1 ? optionalInt(fields[next + 1]) : null;
        return new Punch(pin, local, local.atZone(zone).toInstant(), status, verify);
    }

    private static Integer optionalInt(String value) {
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(trimmed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid number '" + abbreviate(trimmed) + "'");
        }
    }

    private static String abbreviate(String value) {
        return value.length() > 12 ? value.substring(0, 12) + "..." : value;
    }
}
