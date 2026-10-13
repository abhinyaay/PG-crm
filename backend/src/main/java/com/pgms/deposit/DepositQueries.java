package com.pgms.deposit;

import com.pgms.common.exception.BadRequestException;
import com.pgms.deposit.DepositDtos.DepositSummary;
import com.pgms.deposit.DepositDtos.DepositTotals;
import com.pgms.security.AccessScope;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Read model of the Deposits tab: one row per stay with figures aggregated from payments (collected / held),
 * open deposit invoices (pending collection) and the deposit ledger, plus the totals cards. Plain SQL because the
 * figures are aggregates over several tables.
 */
@Component
public class DepositQueries {

    /** Whitelisted sort properties -> SQL expressions. */
    static final Map<String, String> SORTS = Map.of(
            "moveOutDate", "a.move_out_date",
            "refundDueBy", "a.refund_due_by",
            "refundDue", "a.refund_due",
            "createdAt", "a.created_at",
            "tenantName", "t.full_name",
            "status", "a.status");

    private static final String OPEN = "('ISSUED', 'PARTIALLY_PAID', 'OVERDUE')";

    private static final String FROM = """
            FROM deposit_accounts a
            JOIN tenants t ON t.id = a.tenant_id
            JOIN properties p ON p.id = a.property_id
            JOIN allocations al ON al.id = a.current_allocation_id
            LEFT JOIN LATERAL (
                SELECT sum(pay.amount) AS collected, sum(pay.amount - pay.refunded_amount) AS held
                FROM deposit_account_allocations m
                JOIN invoices i ON i.allocation_id = m.allocation_id AND i.invoice_type = 'SECURITY_DEPOSIT'
                JOIN payments pay ON pay.invoice_id = i.id
                WHERE m.deposit_account_id = a.id) f ON TRUE
            """;

    private static final String DETAILS = """
            LEFT JOIN LATERAL (
                SELECT sum(i.total_amount - i.amount_paid) AS pending
                FROM deposit_account_allocations m
                JOIN invoices i ON i.allocation_id = m.allocation_id AND i.invoice_type = 'SECURITY_DEPOSIT'
                WHERE m.deposit_account_id = a.id AND i.status IN %s) pc ON TRUE
            LEFT JOIN LATERAL (
                SELECT sum(x.amount) FILTER (WHERE x.type = 'DUES_ADJUSTED') AS dues_adjusted,
                       sum(x.amount) FILTER (WHERE x.type = 'DEDUCTION') AS deductions,
                       sum(x.amount) FILTER (WHERE x.type = 'FORFEIT') AS forfeited,
                       sum(x.amount) FILTER (WHERE x.type = 'REFUND') AS refunded,
                       sum(x.amount) FILTER (WHERE x.type = 'RENT_CREDIT') AS rent_credit
                FROM deposit_transactions x WHERE x.deposit_account_id = a.id) tx ON TRUE
            """.formatted(OPEN);

    private static final String COLUMNS = """
            SELECT a.id, a.tenant_id, t.full_name, a.property_id, p.name AS property_name, a.current_allocation_id,
                   a.root_allocation_id, al.bed_id, al.status AS allocation_status, al.security_deposit, a.status,
                   a.move_out_date, a.refund_due, a.refund_due_by, a.settled_at, a.forfeit_reason,
                   coalesce(f.collected, 0) AS collected, coalesce(f.held, 0) AS held, coalesce(pc.pending, 0) AS pending,
                   coalesce(tx.dues_adjusted, 0) AS dues_adjusted, coalesce(tx.deductions, 0) AS deductions,
                   coalesce(tx.forfeited, 0) AS forfeited, coalesce(tx.refunded, 0) AS refunded,
                   coalesce(tx.rent_credit, 0) AS rent_credit
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public DepositQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Filters of the list; null = not filtered. */
    public record Filter(AccessScope scope, Long propertyId, Long tenantId, Long accountId, DepositStatus status,
                         Boolean overdue, LocalDate today) {
    }

    public List<DepositSummary> page(Filter filter, Sort sort, int limit, long offset) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(filter, params, true);
        params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(COLUMNS + FROM + DETAILS + where + orderBy(sort) + " LIMIT :limit OFFSET :offset", params,
                (rs, n) -> map(rs, filter.today()));
    }

    public long count(Filter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        Long count = jdbc.queryForObject("SELECT count(*) FROM deposit_accounts a JOIN tenants t ON t.id = a.tenant_id "
                + where(filter, params, true), params, Long.class);
        return count == null ? 0 : count;
    }

    public DepositTotals totals(Filter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(filter, params, false);
        return jdbc.queryForObject("""
                SELECT coalesce(sum(f.held) FILTER (WHERE a.status = 'HELD'), 0) AS total_held,
                       count(*) FILTER (WHERE a.status = 'HELD') AS held_count,
                       coalesce(sum(a.refund_due) FILTER (WHERE a.status IN ('REFUND_DUE', 'PARTIALLY_REFUNDED')), 0) AS due,
                       count(*) FILTER (WHERE a.status IN ('REFUND_DUE', 'PARTIALLY_REFUNDED')) AS due_count,
                       coalesce(sum(a.refund_due) FILTER (WHERE a.status IN ('REFUND_DUE', 'PARTIALLY_REFUNDED')
                           AND a.refund_due_by < :today), 0) AS overdue,
                       count(*) FILTER (WHERE a.status IN ('REFUND_DUE', 'PARTIALLY_REFUNDED') AND a.refund_due_by < :today)
                           AS overdue_count
                """ + FROM + where, params.addValue("today", filter.today()), (rs, n) -> new DepositTotals(
                rs.getBigDecimal("total_held"), rs.getLong("held_count"), rs.getBigDecimal("due"), rs.getLong("due_count"),
                rs.getBigDecimal("overdue"), rs.getLong("overdue_count")));
    }

    private static String where(Filter filter, MapSqlParameterSource params, boolean withStatus) {
        StringBuilder sql = new StringBuilder(" WHERE TRUE");
        AccessScope scope = filter.scope();
        if (scope != null && scope.organizationId() != null) {
            sql.append(" AND a.organization_id = :org");
            params.addValue("org", scope.organizationId());
        }
        if (scope != null && scope.propertyIds() != null) {
            sql.append(" AND a.property_id IN (:properties)");
            params.addValue("properties", scope.propertyIds());
        }
        if (filter.propertyId() != null) {
            sql.append(" AND a.property_id = :propertyId");
            params.addValue("propertyId", filter.propertyId());
        }
        if (filter.tenantId() != null) {
            sql.append(" AND a.tenant_id = :tenantId");
            params.addValue("tenantId", filter.tenantId());
        }
        if (filter.accountId() != null) {
            sql.append(" AND a.id = :accountId");
            params.addValue("accountId", filter.accountId());
        }
        if (withStatus && filter.status() != null) {
            sql.append(" AND a.status = :status");
            params.addValue("status", filter.status().name());
        }
        if (withStatus && Boolean.TRUE.equals(filter.overdue())) {
            sql.append(" AND a.status IN ('REFUND_DUE', 'PARTIALLY_REFUNDED') AND a.refund_due_by < :overdueToday");
            params.addValue("overdueToday", filter.today());
        }
        return sql.append(' ').toString();
    }

    private static String orderBy(Sort sort) {
        StringBuilder sql = new StringBuilder(" ORDER BY ");
        if (sort == null || sort.isUnsorted()) {
            return sql.append("a.refund_due_by ASC NULLS LAST, a.id DESC").toString();
        }
        for (Sort.Order order : sort) {
            String column = SORTS.get(order.getProperty());
            if (column == null) {
                throw new BadRequestException("INVALID_SORT",
                        "Cannot sort by '" + order.getProperty() + "'. Allowed: " + SORTS.keySet());
            }
            sql.append(column).append(order.isAscending() ? " ASC NULLS LAST, " : " DESC NULLS LAST, ");
        }
        return sql.append("a.id DESC").toString();
    }

    private static DepositSummary map(ResultSet rs, LocalDate today) throws SQLException {
        DepositStatus status = DepositStatus.valueOf(rs.getString("status"));
        LocalDate refundDueBy = rs.getObject("refund_due_by", LocalDate.class);
        Timestamp settledAt = rs.getTimestamp("settled_at");
        BigDecimal refundDue = rs.getBigDecimal("refund_due");
        return new DepositSummary(rs.getLong("id"), rs.getLong("tenant_id"), rs.getString("full_name"),
                rs.getLong("property_id"), rs.getString("property_name"), rs.getLong("current_allocation_id"),
                rs.getLong("root_allocation_id"), rs.getLong("bed_id"), rs.getString("allocation_status"), status,
                rs.getBigDecimal("security_deposit"), rs.getBigDecimal("collected"), rs.getBigDecimal("pending"),
                rs.getBigDecimal("dues_adjusted"), rs.getBigDecimal("deductions"), rs.getBigDecimal("forfeited"),
                rs.getBigDecimal("refunded"), rs.getBigDecimal("rent_credit"), rs.getBigDecimal("held"), refundDue,
                rs.getObject("move_out_date", LocalDate.class), refundDueBy,
                status.refundPending() && refundDueBy != null && refundDueBy.isBefore(today),
                settledAt == null ? null : settledAt.toInstant(), rs.getString("forfeit_reason"));
    }
}
