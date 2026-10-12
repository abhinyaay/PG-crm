package com.pgms.common.util;

import java.time.Clock;
import java.time.YearMonth;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Human-friendly, collision-free business references (BK-202610-000042) backed by PostgreSQL sequences.
 * Sequences are non-transactional, so gaps are possible after rollbacks - acceptable for references
 * (statutory invoice numbering without gaps would need a locked counter table instead).
 */
@Component
public class ReferenceNumberGenerator {

    public enum Kind {
        BOOKING("BK", "booking_reference_seq"),
        INVOICE("INV", "invoice_number_seq"),
        RECEIPT("RCPT", "receipt_number_seq"),
        REFUND("RFD", "refund_reference_seq"),
        MAINTENANCE("MNT", "maintenance_ticket_seq"),
        COMPLAINT("CMP", "complaint_number_seq");

        private final String prefix;
        private final String sequence;

        Kind(String prefix, String sequence) {
            this.prefix = prefix;
            this.sequence = sequence;
        }
    }

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public ReferenceNumberGenerator(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public String next(Kind kind) {
        Long value = jdbcTemplate.queryForObject("SELECT nextval('" + kind.sequence + "')", Long.class);
        YearMonth month = YearMonth.now(clock);
        return String.format("%s-%d%02d-%06d", kind.prefix, month.getYear(), month.getMonthValue(), value);
    }
}
