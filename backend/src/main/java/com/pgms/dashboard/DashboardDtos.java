package com.pgms.dashboard;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class DashboardDtos {

    private DashboardDtos() {
    }

    public record BedStats(long total, long occupied, long available, long reserved, long maintenance,
                           BigDecimal occupancyRate) {
    }

    public record UpcomingCheckout(Long allocationId, Long tenantId, String tenantName, Long propertyId, String roomNumber,
                                   String bedCode, LocalDate expectedEndDate) {
    }

    public record RecentPayment(Long paymentId, String receiptNumber, Long tenantId, String tenantName, BigDecimal amount,
                                String method, Instant paidAt) {
    }

    /** SUPER_ADMIN / PROPERTY_ADMIN / MANAGER view, aggregated over the caller's scope. */
    public record OperationsDashboard(
            String scope,
            long activeTenants,
            BedStats beds,
            BigDecimal monthlyRevenue,
            BigDecimal outstandingRent,
            long overdueInvoices,
            long pendingMaintenance,
            long slaBreachedMaintenance,
            long openComplaints,
            long escalatedComplaints,
            List<UpcomingCheckout> upcomingCheckouts,
            List<RecentPayment> recentPayments,
            Instant generatedAt) {
    }

    public record TicketSummary(Long id, String number, String title, String priority, String status, Instant dueAt,
                                boolean breached) {
    }

    public record StaffDashboard(long assignedOpen, long inProgress, long slaBreached, long assignedComplaints,
                                 List<TicketSummary> nextTickets, Instant generatedAt) {
    }

    public record TenantDashboard(
            Long tenantId,
            String status,
            String propertyName,
            String roomNumber,
            String bedCode,
            LocalDate stayingSince,
            BigDecimal monthlyRent,
            BigDecimal outstanding,
            BigDecimal depositHeld,
            LocalDate nextDueDate,
            long openMaintenanceRequests,
            long openComplaints,
            List<RecentPayment> recentPayments,
            Instant generatedAt) {
    }
}
