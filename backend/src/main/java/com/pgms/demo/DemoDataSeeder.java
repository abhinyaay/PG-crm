package com.pgms.demo;

import com.pgms.common.domain.Priority;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Generates a realistic, deterministic demo organization with ~14 months of history (stays, rent invoices,
 * on-time/late/missed payments, deposit refunds, maintenance tickets, complaints) so the dashboard and the
 * analytics service have meaningful data. Active only with the {@code demo} profile and only once.
 *
 * <p>It writes with SQL rather than through the domain services on purpose: the services correctly refuse to
 * back-date operations by more than a month, but history has to be back-dated. All rows still satisfy every
 * database constraint (no overlapping allocations, consistent invoice totals, etc.).
 */
@Component
@Profile("demo")
@Order(10)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final String ORG_SLUG = "demo-sunrise";
    private static final int HISTORY_MONTHS = 14;
    private static final String[] MALE = {"Aarav", "Vihaan", "Arjun", "Rohan", "Karthik", "Siddharth", "Aditya", "Rahul",
        "Nikhil", "Pranav", "Varun", "Harsh", "Manish", "Kunal", "Abhishek", "Yash"};
    private static final String[] FEMALE = {"Ananya", "Diya", "Priya", "Sneha", "Kavya", "Ishita", "Meera", "Pooja",
        "Riya", "Shreya", "Neha", "Aditi", "Tanvi", "Nisha", "Lavanya", "Divya"};
    private static final String[] SURNAMES = {"Sharma", "Iyer", "Reddy", "Patel", "Nair", "Gupta", "Rao", "Menon",
        "Kulkarni", "Das", "Singh", "Joshi", "Bose", "Hegde", "Pillai", "Verma"};
    private static final String[] OCCUPATIONS = {"Software Engineer", "Student", "Analyst", "Designer", "Consultant",
        "Data Scientist", "Sales Executive", "Product Manager"};
    private static final String[] MAINTENANCE_CATEGORIES = {"ELECTRICAL", "PLUMBING", "CARPENTRY", "CLEANING", "APPLIANCE",
        "INTERNET", "PEST_CONTROL", "OTHER"};
    private static final String[] COMPLAINT_CATEGORIES = {"NOISE", "CLEANLINESS", "FOOD", "STAFF_BEHAVIOUR", "SECURITY",
        "BILLING", "ROOMMATE", "OTHER"};
    private static final String[] METHODS = {"UPI", "UPI", "UPI", "BANK_TRANSFER", "CASH", "CARD"};

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final String demoPassword;
    private final Random random = new Random(42);
    private ZoneId zone;
    private LocalDate today;
    private int phoneCounter = 1000;

    public DemoDataSeeder(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, Clock clock,
                          @Value("${APP_DEMO_PASSWORD:}") String demoPassword) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.demoPassword = demoPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Integer existing = jdbc.queryForObject("SELECT count(*) FROM organizations WHERE slug = ?", Integer.class, ORG_SLUG);
        if (existing != null && existing > 0) {
            log.info("Demo data already present - skipping");
            return;
        }
        if (!StringUtils.hasText(demoPassword)) {
            log.warn("Profile 'demo' is active but APP_DEMO_PASSWORD is not set - demo data not generated");
            return;
        }
        zone = clock.getZone();
        today = LocalDate.now(clock);
        long started = System.currentTimeMillis();

        String hash = passwordEncoder.encode(demoPassword);
        long orgId = insertId("INSERT INTO organizations (name, slug, contact_email, contact_phone, created_at) "
                + "VALUES ('Sunrise Living', ?, 'ops@sunrise.example.com', '+918000000000', ?) RETURNING id",
                ORG_SLUG, ts(today.minusMonths(HISTORY_MONTHS + 1)));
        long adminId = user(orgId, 2, "owner@sunrise.example.com", "Sunrise Owner", hash);
        long manager1 = user(orgId, 3, "manager.blr@sunrise.example.com", "Ravi Manager", hash);
        long manager2 = user(orgId, 3, "manager.wf@sunrise.example.com", "Anita Manager", hash);
        long staff1 = user(orgId, 4, "staff1@sunrise.example.com", "Suresh Technician", hash);
        long staff2 = user(orgId, 4, "staff2@sunrise.example.com", "Mohan Electrician", hash);
        long staff3 = user(orgId, 4, "staff3@sunrise.example.com", "Lakshmi Housekeeping", hash);

        List<PropertySeed> properties = List.of(
                new PropertySeed("SUN-KOR", "Sunrise Koramangala", "MEN", "Koramangala 5th Block", "560095",
                        List.of(manager1, staff1, staff3)),
                new PropertySeed("SUN-HSR", "Sunrise HSR Layout", "WOMEN", "HSR Layout Sector 2", "560102",
                        List.of(manager1, staff2, staff3)),
                new PropertySeed("SUN-WFD", "Sunrise Whitefield", "CO_LIVING", "ITPL Main Road, Whitefield", "560066",
                        List.of(manager2, staff1, staff2)));

        int stays = 0;
        for (PropertySeed seed : properties) {
            long propertyId = insertId("""
                    INSERT INTO properties (organization_id, code, name, property_type, status, address_line1, city, state,
                                            postal_code, country, contact_phone, rent_due_day, created_at, created_by)
                    VALUES (?, ?, ?, ?, 'ACTIVE', ?, 'Bengaluru', 'Karnataka', ?, 'India', '+918000000001', 5, ?, ?)
                    RETURNING id""", orgId, seed.code, seed.name, seed.type, seed.street, seed.postalCode,
                    ts(today.minusMonths(HISTORY_MONTHS + 1)), adminId);
            for (Long userId : seed.staff) {
                jdbc.update("INSERT INTO property_assignments (property_id, user_id, assigned_by) VALUES (?, ?, ?)",
                        propertyId, userId, adminId);
            }
            List<BedSeed> beds = buildStructure(propertyId, adminId);
            for (BedSeed bed : beds) {
                stays += simulateBed(orgId, propertyId, seed.type, bed, manager1);
            }
            seedTickets(orgId, propertyId, beds, seed.staff, adminId);
        }
        createTenantPortalUser(orgId, hash);
        jdbc.update("UPDATE beds SET status = 'MAINTENANCE' WHERE id IN (SELECT id FROM beds WHERE status = 'AVAILABLE' "
                + "AND property_id IN (SELECT id FROM properties WHERE organization_id = ?) ORDER BY id LIMIT 2)", orgId);
        log.info("Demo data generated in {} ms: 3 properties, {} stays. Log in as owner@sunrise.example.com, "
                + "manager.blr@sunrise.example.com, staff1@sunrise.example.com or tenant.demo@example.com "
                + "with APP_DEMO_PASSWORD", System.currentTimeMillis() - started, stays);
    }

    private List<BedSeed> buildStructure(long propertyId, long adminId) {
        long buildingId = insertId("INSERT INTO buildings (property_id, code, name, created_by) VALUES (?, 'A', 'Block A', ?) "
                + "RETURNING id", propertyId, adminId);
        List<BedSeed> beds = new ArrayList<>();
        String[] types = {"SINGLE", "DOUBLE", "DOUBLE", "TRIPLE", "DOUBLE"};
        for (int floorNo = 1; floorNo <= 3; floorNo++) {
            long floorId = insertId("INSERT INTO floors (building_id, floor_number, label, created_by) VALUES (?, ?, ?, ?) "
                    + "RETURNING id", buildingId, floorNo, "Floor " + floorNo, adminId);
            for (int r = 1; r <= types.length; r++) {
                String type = types[r - 1];
                int capacity = switch (type) {
                    case "SINGLE" -> 1;
                    case "TRIPLE" -> 3;
                    default -> 2;
                };
                boolean ac = r % 2 == 1;
                BigDecimal rent = BigDecimal.valueOf(switch (type) {
                    case "SINGLE" -> 16000;
                    case "TRIPLE" -> 8000;
                    default -> 11000;
                } + (ac ? 1500 : 0));
                long roomId = insertId("""
                        INSERT INTO rooms (property_id, floor_id, room_number, room_type, capacity, monthly_rent,
                                           security_deposit, air_conditioned, attached_bathroom, created_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", propertyId, floorId,
                        String.valueOf(floorNo * 100 + r), type, capacity, rent, rent.multiply(BigDecimal.TWO), ac, r != 4,
                        adminId);
                for (int b = 0; b < capacity; b++) {
                    long bedId = insertId("INSERT INTO beds (room_id, property_id, bed_code, status, created_by) "
                            + "VALUES (?, ?, ?, 'AVAILABLE', ?) RETURNING id", roomId, propertyId,
                            String.valueOf((char) ('A' + b)), adminId);
                    beds.add(new BedSeed(bedId, roomId, rent, rent.multiply(BigDecimal.TWO)));
                }
            }
        }
        return beds;
    }

    /** Walks one bed's timeline: vacancy gap, stay, vacancy gap, stay ... until today. */
    private int simulateBed(long orgId, long propertyId, String propertyType, BedSeed bed, long managerId) {
        LocalDate cursor = today.minusMonths(HISTORY_MONTHS).plusDays(random.nextInt(20));
        int stays = 0;
        while (cursor.isBefore(today)) {
            LocalDate start = cursor.plusDays(random.nextInt(25));
            if (!start.isBefore(today)) {
                break;
            }
            LocalDate end = start.plusDays(60 + random.nextInt(300));
            boolean ongoing = !end.isBefore(today);
            if (ongoing && random.nextInt(100) < 12) {
                break; // some beds are simply vacant today
            }
            createStay(orgId, propertyId, propertyType, bed, start, ongoing ? null : end, managerId);
            stays++;
            if (ongoing) {
                jdbc.update("UPDATE beds SET status = 'OCCUPIED' WHERE id = ?", bed.id);
                break;
            }
            cursor = end;
        }
        return stays;
    }

    private void createStay(long orgId, long propertyId, String propertyType, BedSeed bed, LocalDate start, LocalDate end,
                            long managerId) {
        boolean female = "WOMEN".equals(propertyType) || "CO_LIVING".equals(propertyType) && random.nextBoolean();
        String first = pick(female ? FEMALE : MALE);
        String name = first + " " + pick(SURNAMES);
        String phone = "+9190000" + String.format("%05d", phoneCounter++);
        String email = first.toLowerCase() + "." + phoneCounter + "@example.com";
        long tenantId = insertId("""
                INSERT INTO tenants (organization_id, property_id, full_name, email, phone, gender, occupation, status,
                                     joining_date, move_out_date, created_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", orgId, propertyId, name, email, phone,
                female ? "FEMALE" : "MALE", pick(OCCUPATIONS), end == null ? "ACTIVE" : "MOVED_OUT", start, end,
                ts(start.minusDays(3)), managerId);
        jdbc.update("INSERT INTO emergency_contacts (tenant_id, name, relationship, phone, is_primary) VALUES (?, ?, ?, ?, true)",
                tenantId, pick(MALE) + " " + pick(SURNAMES), "Parent", "+9191000" + String.format("%05d", phoneCounter));

        LocalDate expectedEnd = end == null && random.nextInt(100) < 10 ? today.plusDays(5 + random.nextInt(25)) : null;
        long allocationId = insertId("""
                INSERT INTO allocations (organization_id, property_id, tenant_id, bed_id, status, start_date, end_date,
                                         expected_end_date, notice_date, end_reason, monthly_rent, security_deposit,
                                         created_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", orgId, propertyId, tenantId, bed.id,
                end == null ? "ACTIVE" : "ENDED", start, end, expectedEnd, expectedEnd == null ? null : latest(start, today.minusDays(3)),
                end == null ? null : "CHECK_OUT", bed.rent, bed.deposit, ts(start), managerId);
        if (expectedEnd != null) {
            jdbc.update("UPDATE tenants SET status = 'ON_NOTICE' WHERE id = ?", tenantId);
        }

        int reliability = random.nextInt(100); // <70 punctual, <90 often late, else defaulter
        long depositPaymentId = invoiceWithPayment(orgId, propertyId, tenantId, allocationId, "SECURITY_DEPOSIT", null,
                start, start, bed.deposit, 0, managerId);
        YearMonth month = YearMonth.from(start);
        LocalDate lastDay = end == null ? today : end.minusDays(1);
        while (!month.isAfter(YearMonth.from(lastDay))) {
            LocalDate periodStart = month.atDay(1).isBefore(start) ? start : month.atDay(1);
            BigDecimal amount = prorate(bed.rent, periodStart);
            LocalDate issue = periodStart;
            LocalDate due = month.atDay(5).isBefore(issue) ? issue : month.atDay(5);
            int lateDays = reliability < 70 ? -random.nextInt(4) : reliability < 90 ? 3 + random.nextInt(20)
                    : (random.nextInt(100) < 50 ? 10 + random.nextInt(30) : Integer.MAX_VALUE);
            invoiceWithPayment(orgId, propertyId, tenantId, allocationId, "RENT", month, issue, due, amount, lateDays, managerId);
            month = month.plusMonths(1);
        }
        if (end != null && depositPaymentId > 0 && random.nextInt(100) < 85) {
            BigDecimal refund = bed.deposit.subtract(BigDecimal.valueOf(random.nextInt(3) * 500L));
            Instant refundedAt = notInFuture(instant(end.plusDays(2 + random.nextInt(10))));
            jdbc.update("""
                    INSERT INTO refunds (refund_reference, organization_id, payment_id, amount, reason, idempotency_key,
                                         request_fingerprint, refunded_at, created_at)
                    VALUES (?, ?, ?, ?, 'Security deposit refund on move-out', ?, 'demo', ?, ?)""",
                    "RFD-DEMO-" + depositPaymentId, orgId, depositPaymentId, refund, "demo-" + UUID.randomUUID(),
                    Timestamp.from(refundedAt), Timestamp.from(refundedAt));
            jdbc.update("UPDATE payments SET refunded_amount = ?, status = ? WHERE id = ?", refund,
                    refund.compareTo(bed.deposit) == 0 ? "REFUNDED" : "PARTIALLY_REFUNDED", depositPaymentId);
        }
    }

    /**
     * Inserts an invoice and (unless unpaid) its payment. {@code lateDays} relative to the due date;
     * {@code Integer.MAX_VALUE} means not paid (yet). Returns the payment id or -1.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    private long invoiceWithPayment(long orgId, long propertyId, long tenantId, long allocationId, String type, YearMonth month,
                                    LocalDate issue, LocalDate due, BigDecimal amount, int lateDays, long managerId) {
        LocalDate paidOn = lateDays == Integer.MAX_VALUE ? null : due.plusDays(lateDays);
        if (paidOn != null && paidOn.isBefore(issue)) {
            paidOn = issue;
        }
        if (paidOn != null && paidOn.isAfter(today)) {
            paidOn = null; // not paid yet
        }
        String status;
        if (paidOn != null) {
            status = "PAID";
        } else if (due.isBefore(today)) {
            status = "OVERDUE";
        } else {
            status = "ISSUED";
        }
        BigDecimal paid = paidOn == null ? BigDecimal.ZERO : amount;
        String number = "INV-DEMO-" + jdbc.queryForObject("SELECT nextval('invoice_number_seq')", Long.class);
        long invoiceId = insertId("""
                INSERT INTO invoices (invoice_number, organization_id, property_id, tenant_id, allocation_id, invoice_type,
                                      billing_month, period_start, period_end, issue_date, due_date, status, subtotal,
                                      total_amount, amount_paid, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", number, orgId, propertyId, tenantId,
                allocationId, type, month == null ? null : month.atDay(1), month == null ? null : issue,
                month == null ? null : month.atEndOfMonth(), issue, due, status, amount, amount, paid, ts(issue));
        jdbc.update("INSERT INTO invoice_line_items (invoice_id, line_type, description, amount, created_at) VALUES (?, ?, ?, ?, ?)",
                invoiceId, type, "RENT".equals(type) ? "Rent for " + month : "Refundable security deposit", amount, ts(issue));
        if (paidOn == null) {
            return -1;
        }
        String receipt = "RCPT-DEMO-" + jdbc.queryForObject("SELECT nextval('receipt_number_seq')", Long.class);
        Instant paidAt = instant(paidOn).plus(Duration.ofHours(10 + random.nextInt(10)));
        if (paidAt.isAfter(clock.instant())) {
            paidAt = clock.instant().minus(Duration.ofMinutes(5 + random.nextInt(120))); // never in the future
        }
        return insertId("""
                INSERT INTO payments (receipt_number, organization_id, property_id, tenant_id, invoice_id, idempotency_key,
                                      request_fingerprint, amount, method, status, paid_at, created_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, 'demo', ?, ?, 'COMPLETED', ?, ?, ?) RETURNING id""", receipt, orgId, propertyId,
                tenantId, invoiceId, "demo-" + UUID.randomUUID(), amount, pick(METHODS), Timestamp.from(paidAt),
                Timestamp.from(paidAt), managerId);
    }

    private void seedTickets(long orgId, long propertyId, List<BedSeed> beds, List<Long> staff, long adminId) {
        Instant now = clock.instant();
        Instant historyStart = instant(today.minusMonths(HISTORY_MONTHS - 1));
        for (int i = 0; i < 55; i++) {
            Instant created = randomInstant(historyStart, now);
            Priority priority = Priority.values()[random.nextInt(Priority.values().length)];
            Instant slaDue = created.plus(priority.resolutionTarget());
            boolean recent = Duration.between(created, now).toDays() < 10;
            String status = recent ? pick(new String[] {"OPEN", "ASSIGNED", "IN_PROGRESS", "RESOLVED"})
                    : (random.nextInt(100) < 92 ? "CLOSED" : "CANCELLED");
            boolean done = "RESOLVED".equals(status) || "CLOSED".equals(status);
            double slaFactor = random.nextInt(100) < 80 ? 0.2 + random.nextDouble() * 0.7 : 1.1 + random.nextDouble() * 2;
            Instant resolved = done ? created.plusSeconds((long) (priority.resolutionTarget().getSeconds() * slaFactor)) : null;
            if (resolved != null && resolved.isAfter(now)) {
                resolved = now;
            }
            Long assignee = "OPEN".equals(status) ? null : staff.get(1 + random.nextInt(staff.size() - 1));
            // ordered timeline (assigned <= started <= resolved <= closed), nothing later than now
            Instant started = notInFuture(created.plusSeconds(3600));
            if (resolved != null && started.isAfter(resolved)) {
                started = resolved;
            }
            Instant assigned = notInFuture(created.plusSeconds(1800));
            if (assigned.isAfter(started)) {
                assigned = started;
            }
            Instant closed = "CLOSED".equals(status) || "CANCELLED".equals(status)
                    ? notInFuture(resolved == null ? created.plusSeconds(7200) : resolved.plusSeconds(3600)) : null;
            boolean breached = resolved != null ? resolved.isAfter(slaDue) : !"CANCELLED".equals(status) && now.isAfter(slaDue);
            long roomId = beds.get(random.nextInt(beds.size())).roomId;
            long ticketId = insertId("""
                    INSERT INTO maintenance_requests (ticket_number, organization_id, property_id, room_id, raised_by_user_id,
                        category, priority, title, description, status, assigned_to_user_id, assigned_at, started_at,
                        resolved_at, closed_at, resolution_notes, sla_due_at, sla_breached, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""",
                    "MNT-DEMO-" + jdbc.queryForObject("SELECT nextval('maintenance_ticket_seq')", Long.class), orgId,
                    propertyId, roomId, staff.get(0), pick(MAINTENANCE_CATEGORIES), priority.name(),
                    "Issue reported in room", "Generated demo request", status, assignee,
                    assignee == null ? null : Timestamp.from(assigned),
                    assignee == null || "ASSIGNED".equals(status) ? null : Timestamp.from(started),
                    resolved == null ? null : Timestamp.from(resolved),
                    closed == null ? null : Timestamp.from(closed),
                    done ? "Fixed and verified" : null, Timestamp.from(slaDue), breached, Timestamp.from(created));
            jdbc.update("INSERT INTO maintenance_status_history (request_id, to_status, changed_by, note, changed_at) "
                    + "VALUES (?, 'OPEN', ?, 'Request created', ?)", ticketId, staff.get(0), Timestamp.from(created));
        }
        for (int i = 0; i < 20; i++) {
            Instant created = randomInstant(historyStart, now);
            Priority priority = Priority.values()[random.nextInt(3)];
            boolean recent = Duration.between(created, now).toDays() < 7;
            int escalation = !recent && random.nextInt(100) < 15 ? 1 + random.nextInt(2) : 0;
            String status = recent ? pick(new String[] {"OPEN", "IN_PROGRESS"}) : escalation > 0 && random.nextBoolean()
                    ? "RESOLVED" : "CLOSED";
            Instant resolved = recent ? null : created.plus(Duration.ofHours(4 + random.nextInt(120)));
            Instant escalatedAt = escalation > 0 ? created.plus(priority.resolutionTarget()) : null;
            if (escalatedAt != null) {
                // an escalated complaint was resolved only after it had been escalated
                resolved = notInFuture(escalatedAt.plus(Duration.between(created, resolved)));
            }
            Instant closedAt = "CLOSED".equals(status) ? notInFuture(resolved.plusSeconds(86_400)) : null;
            jdbc.update("""
                    INSERT INTO complaints (complaint_number, organization_id, property_id, raised_by_user_id, category,
                        priority, subject, description, status, assigned_to_user_id, escalation_level, escalated_at, due_at,
                        resolution, resolved_at, closed_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    "CMP-DEMO-" + jdbc.queryForObject("SELECT nextval('complaint_number_seq')", Long.class), orgId,
                    propertyId, adminId, pick(COMPLAINT_CATEGORIES), priority.name(), "Resident complaint",
                    "Generated demo complaint", status, staff.get(0), escalation,
                    escalatedAt == null ? null : Timestamp.from(escalatedAt),
                    Timestamp.from(created.plus(priority.resolutionTarget())), resolved == null ? null : "Addressed with resident",
                    resolved == null ? null : Timestamp.from(resolved),
                    closedAt == null ? null : Timestamp.from(closedAt), Timestamp.from(created));
        }
    }

    /** Gives one active resident a TENANT login so the tenant dashboard/portal can be tried. */
    private void createTenantPortalUser(long orgId, String hash) {
        Long tenantId = jdbc.query("SELECT id FROM tenants WHERE organization_id = ? AND status = 'ACTIVE' ORDER BY id LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null, orgId);
        if (tenantId == null) {
            return;
        }
        long userId = user(orgId, 5, "tenant.demo@example.com", "Demo Tenant", hash);
        jdbc.update("UPDATE tenants SET user_id = ?, email = 'tenant.demo@example.com' WHERE id = ?", userId, tenantId);
    }

    private long user(long orgId, int roleId, String email, String name, String hash) {
        return insertId("INSERT INTO users (organization_id, role_id, email, password_hash, full_name, status) "
                + "VALUES (?, ?, ?, ?, ?, 'ACTIVE') RETURNING id", orgId, roleId, email, hash, name);
    }

    private long insertId(String sql, Object... args) {
        Long id = jdbc.queryForObject(sql, Long.class, args);
        if (id == null) {
            throw new IllegalStateException("Insert returned no id: " + sql);
        }
        return id;
    }

    private Instant instant(LocalDate date) {
        return date.atTime(9, 0).atZone(zone).toInstant();
    }

    private Timestamp ts(LocalDate date) {
        return Timestamp.from(instant(date));
    }

    private Instant notInFuture(Instant value) {
        Instant now = clock.instant();
        return value.isAfter(now) ? now : value;
    }

    private static LocalDate latest(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private Instant randomInstant(Instant from, Instant to) {
        long span = Math.max(1, to.getEpochSecond() - from.getEpochSecond());
        return from.plusSeconds((long) (random.nextDouble() * span));
    }

    private static BigDecimal prorate(BigDecimal rent, LocalDate from) {
        int days = YearMonth.from(from).lengthOfMonth();
        int remaining = days - from.getDayOfMonth() + 1;
        return rent.multiply(BigDecimal.valueOf(remaining)).divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
    }

    private String pick(String[] values) {
        return values[random.nextInt(values.length)];
    }

    private record PropertySeed(String code, String name, String type, String street, String postalCode, List<Long> staff) {
    }

    private record BedSeed(long id, long roomId, BigDecimal rent, BigDecimal deposit) {
    }
}
