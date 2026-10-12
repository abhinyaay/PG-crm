package com.pgms.dashboard;

import com.pgms.billing.Invoice;
import com.pgms.billing.InvoiceRepository;
import com.pgms.billing.InvoiceStatus;
import com.pgms.billing.PaymentRepository;
import com.pgms.booking.Allocation;
import com.pgms.booking.AllocationRepository;
import com.pgms.booking.AllocationStatus;
import com.pgms.dashboard.DashboardDtos.OperationsDashboard;
import com.pgms.dashboard.DashboardDtos.StaffDashboard;
import com.pgms.dashboard.DashboardDtos.TenantDashboard;
import com.pgms.property.PropertyRepository;
import com.pgms.room.Bed;
import com.pgms.room.BedRepository;
import com.pgms.security.AccessGuard;
import com.pgms.security.AccessScope;
import com.pgms.tenant.Tenant;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {

    public static final String CACHE_NAME = "dashboard";

    private final DashboardQueries queries;
    private final AccessGuard accessGuard;
    private final AllocationRepository allocationRepository;
    private final BedRepository bedRepository;
    private final PropertyRepository propertyRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final Clock clock;

    public DashboardService(DashboardQueries queries, AccessGuard accessGuard, AllocationRepository allocationRepository,
                            BedRepository bedRepository, PropertyRepository propertyRepository,
                            InvoiceRepository invoiceRepository, PaymentRepository paymentRepository, Clock clock) {
        this.queries = queries;
        this.accessGuard = accessGuard;
        this.allocationRepository = allocationRepository;
        this.bedRepository = bedRepository;
        this.propertyRepository = propertyRepository;
        this.invoiceRepository = invoiceRepository;
        this.paymentRepository = paymentRepository;
        this.clock = clock;
    }

    /** Stable cache key describing exactly what the caller may see (so cached data is never shared across scopes). */
    public String scopeKey(Long propertyId) {
        AccessScope scope = accessGuard.scopeFor(propertyId);
        String properties = scope.propertyIds() == null ? "*" : new TreeSet<>(scope.propertyIds()).toString();
        return "org=" + (scope.organizationId() == null ? "*" : scope.organizationId()) + ";props=" + properties;
    }

    /**
     * Operator KPIs, cached in Redis for a short TTL (60s by default): the figures are aggregates over many rows,
     * requested on every landing page load, and a minute of staleness is acceptable for a dashboard.
     */
    @Cacheable(cacheNames = CACHE_NAME, key = "#scopeKey")
    @Transactional(readOnly = true)
    public OperationsDashboard operations(String scopeKey, Long propertyId) {
        AccessScope scope = accessGuard.scopeFor(propertyId);
        ZoneId zone = clock.getZone();
        YearMonth month = YearMonth.now(clock);
        LocalDate today = LocalDate.now(clock);
        if (scope.isEmpty()) {
            return new OperationsDashboard(scopeKey, 0, new DashboardDtos.BedStats(0, 0, 0, 0, 0, BigDecimal.ZERO),
                    BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0, 0, 0, List.of(), List.of(), clock.instant());
        }
        return new OperationsDashboard(
                scopeKey,
                queries.count("tenants", "x.deleted_at IS NULL AND x.status IN ('ACTIVE', 'ON_NOTICE')", scope),
                queries.bedStats(scope),
                queries.rentCollected(scope, month.atDay(1).atStartOfDay(zone).toInstant(),
                        month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()),
                queries.outstanding(scope),
                queries.count("invoices", "x.status = 'OVERDUE'", scope),
                queries.count("maintenance_requests", "x.status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS')", scope),
                queries.count("maintenance_requests", "x.status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS') AND x.sla_breached", scope),
                queries.count("complaints", "x.status IN ('OPEN', 'IN_PROGRESS', 'ESCALATED')", scope),
                queries.count("complaints", "x.status = 'ESCALATED'", scope),
                queries.upcomingCheckouts(scope, today.plusDays(30)),
                queries.recentPayments(scope, null, 10),
                clock.instant());
    }

    @Transactional(readOnly = true)
    public StaffDashboard staff() {
        Long userId = accessGuard.currentUser().id();
        return new StaffDashboard(
                queries.countForAssignee("maintenance_requests", "'ASSIGNED', 'IN_PROGRESS'", userId),
                queries.countForAssignee("maintenance_requests", "'IN_PROGRESS'", userId),
                queries.breachedForAssignee(userId),
                queries.countForAssignee("complaints", "'OPEN', 'IN_PROGRESS', 'ESCALATED'", userId),
                queries.nextTickets(userId),
                clock.instant());
    }

    /** Tenants see their own live figures (never cached: they check right after paying). */
    @Transactional(readOnly = true)
    public TenantDashboard tenant() {
        Tenant tenant = accessGuard.requireCurrentTenant();
        Optional<Allocation> allocation = allocationRepository.findByTenantIdAndStatus(tenant.getId(), AllocationStatus.ACTIVE);
        Optional<Bed> bed = allocation.flatMap(a -> bedRepository.findById(a.getBedId()));
        String propertyName = propertyRepository.findById(tenant.getPropertyId()).map(p -> p.getName()).orElse(null);
        LocalDate nextDue = invoiceRepository.findByTenantIdAndStatusInOrderByDueDate(tenant.getId(),
                        EnumSet.of(InvoiceStatus.ISSUED, InvoiceStatus.PARTIALLY_PAID, InvoiceStatus.OVERDUE))
                .stream().findFirst().map(Invoice::getDueDate).orElse(null);
        return new TenantDashboard(
                tenant.getId(),
                tenant.getStatus().name(),
                propertyName,
                bed.map(b -> b.getRoom().getRoomNumber()).orElse(null),
                bed.map(Bed::getBedCode).orElse(null),
                tenant.getJoiningDate(),
                allocation.map(Allocation::getMonthlyRent).orElse(null),
                invoiceRepository.outstandingForTenant(tenant.getId()),
                paymentRepository.depositHeldForTenant(tenant.getId()),
                nextDue,
                queries.countForTenant("maintenance_requests", "'OPEN', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED'", tenant.getId()),
                queries.countForTenant("complaints", "'OPEN', 'IN_PROGRESS', 'ESCALATED', 'RESOLVED'", tenant.getId()),
                queries.recentPayments(null, tenant.getId(), 5),
                clock.instant());
    }
}
