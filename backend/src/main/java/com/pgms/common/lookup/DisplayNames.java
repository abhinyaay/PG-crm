package com.pgms.common.lookup;

import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Resolves display names (tenant names, user names, bed/room codes) for a whole page of responses with at most one
 * query per kind of name - never one per row. Reads plain columns of the base tables (including soft-deleted rows, so
 * history keeps its names) and does not flush the persistence context: names are never changed by the transactions
 * that render them.
 */
@Component
public class DisplayNames {

    private final EntityManager entityManager;

    /** The shared, transaction-bound EntityManager proxy: queries join the caller's transaction. */
    public DisplayNames(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Bed code and room number of a bed. */
    public record BedLabel(String bedCode, String roomNumber) {
    }

    /** Resolved names; lookups of unknown or {@code null} ids return {@code null}. */
    public record Index(Map<Long, String> tenants, Map<Long, String> users, Map<Long, BedLabel> beds) {

        public static final Index EMPTY = new Index(Map.of(), Map.of(), Map.of());

        public String tenant(Long id) {
            return id == null ? null : tenants.get(id);
        }

        public String user(Long id) {
            return id == null ? null : users.get(id);
        }

        public String bedCode(Long id) {
            BedLabel label = id == null ? null : beds.get(id);
            return label == null ? null : label.bedCode();
        }

        public String roomNumber(Long id) {
            BedLabel label = id == null ? null : beds.get(id);
            return label == null ? null : label.roomNumber();
        }
    }

    public Index lookup(Collection<Long> tenantIds, Collection<Long> userIds, Collection<Long> bedIds) {
        return new Index(names("SELECT id, full_name FROM tenants WHERE id IN (:ids)", tenantIds),
                names("SELECT id, full_name FROM users WHERE id IN (:ids)", userIds),
                bedLabels(bedIds));
    }

    public Index tenants(Collection<Long> tenantIds) {
        return lookup(tenantIds, Set.of(), Set.of());
    }

    /** Distinct non-null ids extracted from a list of rows. */
    @SafeVarargs
    public static <T> Set<Long> ids(Collection<T> rows, Function<T, Long>... extractors) {
        return rows.stream()
                .flatMap(row -> Arrays.stream(extractors).map(e -> e.apply(row)))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private Map<Long, String> names(String sql, Collection<Long> ids) {
        Map<Long, String> result = new HashMap<>();
        for (Object[] row : rows(sql, ids)) {
            result.put(((Number) row[0]).longValue(), (String) row[1]);
        }
        return result;
    }

    private Map<Long, BedLabel> bedLabels(Collection<Long> ids) {
        Map<Long, BedLabel> result = new HashMap<>();
        for (Object[] row : rows("SELECT b.id, b.bed_code, r.room_number FROM beds b JOIN rooms r ON r.id = b.room_id "
                + "WHERE b.id IN (:ids)", ids)) {
            result.put(((Number) row[0]).longValue(), new BedLabel((String) row[1], (String) row[2]));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> rows(String sql, Collection<Long> ids) {
        Set<Long> distinct = ids == null ? Set.of()
                : ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (distinct.isEmpty()) {
            return List.of();
        }
        return entityManager.createNativeQuery(sql)
                .setParameter("ids", distinct)
                .setFlushMode(FlushModeType.COMMIT)
                .getResultList();
    }
}
