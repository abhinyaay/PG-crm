package com.pgms.dashboard;

import com.pgms.dashboard.DashboardDtos.BedStats;
import com.pgms.dashboard.DashboardDtos.RecentPayment;
import com.pgms.dashboard.DashboardDtos.TicketSummary;
import com.pgms.dashboard.DashboardDtos.UpcomingCheckout;
import com.pgms.security.AccessScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Read-side aggregate queries. Plain SQL is the right tool for KPI roll-ups: one round-trip per figure, using the
 * same indexes as the OLTP queries, and no entity hydration.
 */
@Component
class DashboardQueries {

    private final NamedParameterJdbcTemplate jdbc;

    DashboardQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** SQL predicate restricting {@code alias.property_id} to the caller's scope. */
    private static String scope(String alias, AccessScope scope, MapSqlParameterSource params) {
        StringBuilder sql = new StringBuilder(" AND ").append(alias).append(".property_id IN (SELECT p.id FROM properties p")
                .append(" WHERE p.deleted_at IS NULL");
        if (scope.organizationId() != null) {
            sql.append(" AND p.organization_id = :orgId");
            params.addValue("orgId", scope.organizationId());
        }
        if (scope.restrictedToProperties()) {
            sql.append(" AND p.id IN (:propertyIds)");
            params.addValue("propertyIds", scope.propertyIds());
        }
        return sql.append(")").toString();
    }

    long count(String table, String where, AccessScope scope) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = "SELECT count(*) FROM " + table + " x WHERE " + where + scope("x", scope, params);
        Long value = jdbc.queryForObject(sql, params, Long.class);
        return value == null ? 0 : value;
    }

    BedStats bedStats(AccessScope scope) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE b.status = 'OCCUPIED') AS occupied,
                       count(*) FILTER (WHERE b.status = 'AVAILABLE') AS available,
                       count(*) FILTER (WHERE b.status = 'RESERVED') AS reserved,
                       count(*) FILTER (WHERE b.status = 'MAINTENANCE') AS maintenance
                FROM beds b WHERE b.deleted_at IS NULL""" + scope("b", scope, params), params);
        long total = ((Number) row.get("total")).longValue();
        long occupied = ((Number) row.get("occupied")).longValue();
        BigDecimal rate = total == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(occupied * 100).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        return new BedStats(total, occupied, ((Number) row.get("available")).longValue(),
                ((Number) row.get("reserved")).longValue(), ((Number) row.get("maintenance")).longValue(), rate);
    }

    /** Net rent collected in [from, to): payments minus refunds, security deposits excluded (they are a liability). */
    BigDecimal rentCollected(AccessScope scope, Instant from, Instant to) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("from", Timestamp.from(from))
                .addValue("to", Timestamp.from(to));
        return jdbc.queryForObject("""
                SELECT coalesce(sum(x.amount - x.refunded_amount), 0) FROM payments x
                JOIN invoices i ON i.id = x.invoice_id
                WHERE i.invoice_type = 'RENT' AND x.paid_at >= :from AND x.paid_at < :to""" + scope("x", scope, params),
                params, BigDecimal.class);
    }

    /** Unpaid rent (incl. charges on rent invoices); deposits are excluded, consistent with {@link #rentCollected}. */
    BigDecimal outstanding(AccessScope scope) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        return jdbc.queryForObject("""
                SELECT coalesce(sum(x.total_amount - x.amount_paid), 0) FROM invoices x
                WHERE x.invoice_type = 'RENT' AND x.status IN ('ISSUED', 'PARTIALLY_PAID', 'OVERDUE')"""
                        + scope("x", scope, params), params,
                BigDecimal.class);
    }

    List<UpcomingCheckout> upcomingCheckouts(AccessScope scope, LocalDate until) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("until", until);
        return jdbc.query("""
                SELECT x.id, x.tenant_id, t.full_name, x.property_id, r.room_number, b.bed_code, x.expected_end_date
                FROM allocations x
                JOIN tenants t ON t.id = x.tenant_id
                JOIN beds b ON b.id = x.bed_id
                JOIN rooms r ON r.id = b.room_id
                WHERE x.status = 'ACTIVE' AND x.expected_end_date IS NOT NULL AND x.expected_end_date <= :until"""
                        + scope("x", scope, params) + " ORDER BY x.expected_end_date LIMIT 20", params,
                (rs, i) -> new UpcomingCheckout(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getString(5),
                        rs.getString(6), rs.getObject(7, LocalDate.class)));
    }

    List<RecentPayment> recentPayments(AccessScope scope, Long tenantId, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit);
        String filter;
        if (tenantId != null) {
            filter = " AND x.tenant_id = :tenantId";
            params.addValue("tenantId", tenantId);
        } else {
            filter = scope("x", scope, params);
        }
        return jdbc.query("""
                SELECT x.id, x.receipt_number, x.tenant_id, t.full_name, x.amount, x.method, x.paid_at
                FROM payments x JOIN tenants t ON t.id = x.tenant_id
                WHERE 1 = 1""" + filter + " ORDER BY x.paid_at DESC LIMIT :limit", params,
                (rs, i) -> new RecentPayment(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                        rs.getBigDecimal(5), rs.getString(6), rs.getTimestamp(7).toInstant()));
    }

    long countForAssignee(String table, String statusList, Long userId) {
        Long value = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE assigned_to_user_id = :userId AND status IN ("
                + statusList + ")", new MapSqlParameterSource("userId", userId), Long.class);
        return value == null ? 0 : value;
    }

    long breachedForAssignee(Long userId) {
        Long value = jdbc.queryForObject("""
                SELECT count(*) FROM maintenance_requests WHERE assigned_to_user_id = :userId
                AND status IN ('ASSIGNED', 'IN_PROGRESS') AND sla_breached""", new MapSqlParameterSource("userId", userId),
                Long.class);
        return value == null ? 0 : value;
    }

    List<TicketSummary> nextTickets(Long userId) {
        return jdbc.query("""
                SELECT id, ticket_number, title, priority, status, sla_due_at, sla_breached FROM maintenance_requests
                WHERE assigned_to_user_id = :userId AND status IN ('ASSIGNED', 'IN_PROGRESS')
                ORDER BY sla_due_at LIMIT 10""", new MapSqlParameterSource("userId", userId),
                (rs, i) -> new TicketSummary(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getTimestamp(6).toInstant(), rs.getBoolean(7)));
    }

    long countForTenant(String table, String statusList, Long tenantId) {
        Long value = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = :tenantId AND status IN ("
                + statusList + ")", new MapSqlParameterSource("tenantId", tenantId), Long.class);
        return value == null ? 0 : value;
    }
}
