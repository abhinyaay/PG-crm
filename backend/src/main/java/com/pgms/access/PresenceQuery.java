package com.pgms.access;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Reads the {@code access_presence} view (latest ALLOWED event per person and property). */
@Component
public class PresenceQuery {

    private final JdbcTemplate jdbc;

    public PresenceQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Row(Long tenantId, Long userId, Instant since, Long eventId, AccessMethod method, Long deviceId) {
    }

    public List<Row> inside(Long propertyId) {
        return jdbc.query("""
                SELECT tenant_id, user_id, since, event_id, method, device_id FROM access_presence
                WHERE property_id = ? AND presence = 'IN' ORDER BY since DESC
                """, (rs, n) -> new Row(
                        rs.getObject("tenant_id", Long.class),
                        rs.getObject("user_id", Long.class),
                        rs.getObject("since", OffsetDateTime.class).toInstant(),
                        rs.getLong("event_id"),
                        AccessMethod.valueOf(rs.getString("method")),
                        rs.getObject("device_id", Long.class)), propertyId);
    }
}
