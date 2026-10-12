package com.pgms.integration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Fast test-data builder writing directly with SQL (the API under test is exercised separately). */
@Component
public class Fixtures {

    public static final String PASSWORD = "Passw0rd123";
    private static final AtomicInteger PHONE = new AtomicInteger(10_000);

    private final JdbcTemplate jdbc;
    private final String passwordHash;

    public Fixtures(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.passwordHash = encoder.encode(PASSWORD);
    }

    public record Org(long id, String slug) {
        public String email(String local) {
            return local + "@" + slug + ".test";
        }
    }

    public record PropertyLayout(long propertyId, long floorId, List<Long> roomIds, List<Long> bedIds) {
    }

    public Org organization() {
        String slug = "org-" + UUID.randomUUID().toString().substring(0, 8);
        Long id = jdbc.queryForObject("INSERT INTO organizations (name, slug) VALUES (?, ?) RETURNING id", Long.class,
                "Test " + slug, slug);
        return new Org(id, slug);
    }

    /** Role ids as seeded by V1: 1 SUPER_ADMIN, 2 PROPERTY_ADMIN, 3 MANAGER, 4 STAFF, 5 TENANT. */
    public long user(Org org, int roleId, String local) {
        return jdbc.queryForObject("INSERT INTO users (organization_id, role_id, email, password_hash, full_name) "
                + "VALUES (?, ?, ?, ?, ?) RETURNING id", Long.class, org.id(), roleId, org.email(local), passwordHash, local);
    }

    /** A property with one floor and {@code rooms} double-sharing rooms (2 beds each), rent 9000, deposit 18000. */
    public PropertyLayout property(Org org, int rooms) {
        String code = "P-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        Long propertyId = jdbc.queryForObject("""
                INSERT INTO properties (organization_id, code, name, property_type, address_line1, city, state, postal_code)
                VALUES (?, ?, ?, 'CO_LIVING', '1 Test Road', 'Bengaluru', 'Karnataka', '560001') RETURNING id""",
                Long.class, org.id(), code, "Property " + code);
        Long buildingId = jdbc.queryForObject("INSERT INTO buildings (property_id, code, name) VALUES (?, 'A', 'A') RETURNING id",
                Long.class, propertyId);
        Long floorId = jdbc.queryForObject("INSERT INTO floors (building_id, floor_number) VALUES (?, 1) RETURNING id",
                Long.class, buildingId);
        List<Long> roomIds = new ArrayList<>();
        List<Long> bedIds = new ArrayList<>();
        for (int r = 1; r <= rooms; r++) {
            Long roomId = jdbc.queryForObject("""
                    INSERT INTO rooms (property_id, floor_id, room_number, room_type, capacity, monthly_rent, security_deposit)
                    VALUES (?, ?, ?, 'DOUBLE', 2, 9000, 18000) RETURNING id""", Long.class, propertyId, floorId, "10" + r);
            roomIds.add(roomId);
            for (String code2 : List.of("A", "B")) {
                bedIds.add(jdbc.queryForObject("INSERT INTO beds (room_id, property_id, bed_code) VALUES (?, ?, ?) RETURNING id",
                        Long.class, roomId, propertyId, code2));
            }
        }
        return new PropertyLayout(propertyId, floorId, roomIds, bedIds);
    }

    public void assign(long userId, long propertyId) {
        jdbc.update("INSERT INTO property_assignments (property_id, user_id) VALUES (?, ?)", propertyId, userId);
    }

    public long tenant(Org org, long propertyId) {
        return jdbc.queryForObject("""
                INSERT INTO tenants (organization_id, property_id, full_name, email, phone)
                VALUES (?, ?, ?, ?, ?) RETURNING id""", Long.class, org.id(), propertyId, "Tenant " + PHONE.get(),
                "tenant" + PHONE.get() + "@" + org.slug() + ".test", "+9190" + PHONE.incrementAndGet() + "0000");
    }

    public void linkTenantUser(long tenantId, long userId) {
        jdbc.update("UPDATE tenants SET user_id = ? WHERE id = ?", userId, tenantId);
    }

    public String bedStatus(long bedId) {
        return jdbc.queryForObject("SELECT status FROM beds WHERE id = ?", String.class, bedId);
    }

    public int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    public BigDecimal decimal(String sql, Object... args) {
        return jdbc.queryForObject(sql, BigDecimal.class, args);
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }
}
