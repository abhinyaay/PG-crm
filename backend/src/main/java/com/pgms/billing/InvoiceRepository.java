package com.pgms.billing;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvoiceRepository extends JpaRepository<Invoice, Long>, JpaSpecificationExecutor<Invoice> {

    /** Payments against one invoice serialise on this lock, making balance checks race-free. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id = :id")
    Optional<Invoice> findByIdForUpdate(@Param("id") Long id);

    @EntityGraph(attributePaths = "lineItems")
    @Query("select i from Invoice i where i.id = :id")
    Optional<Invoice> findWithLines(@Param("id") Long id);

    List<Invoice> findByTenantIdAndStatusInOrderByDueDate(Long tenantId, Collection<InvoiceStatus> statuses);

    List<Invoice> findByAllocationIdOrderByIssueDate(Long allocationId);

    @Query("""
            select i.id from Invoice i
            where i.tenantId = :tenantId and i.status in (com.pgms.billing.InvoiceStatus.ISSUED,
              com.pgms.billing.InvoiceStatus.PARTIALLY_PAID, com.pgms.billing.InvoiceStatus.OVERDUE)
            order by i.id
            """)
    List<Long> findOpenIdsByTenant(@Param("tenantId") Long tenantId);

    @Query("""
            select coalesce(sum(i.totalAmount - i.amountPaid), 0) from Invoice i
            where i.tenantId = :tenantId and i.status in (com.pgms.billing.InvoiceStatus.ISSUED,
              com.pgms.billing.InvoiceStatus.PARTIALLY_PAID, com.pgms.billing.InvoiceStatus.OVERDUE)
            """)
    BigDecimal outstandingForTenant(@Param("tenantId") Long tenantId);

    /** Open invoices whose due date has passed but are not yet flagged - checked one by one (optimistic lock safe). */
    @Query("""
            select i.id from Invoice i
            where i.status in (com.pgms.billing.InvoiceStatus.ISSUED, com.pgms.billing.InvoiceStatus.PARTIALLY_PAID)
              and i.dueDate < :today
            """)
    List<Long> findIdsBecomingOverdue(@Param("today") LocalDate today);

    @Query("""
            select i from Invoice i
            where i.status in (com.pgms.billing.InvoiceStatus.ISSUED, com.pgms.billing.InvoiceStatus.PARTIALLY_PAID)
              and i.dueDate = :dueDate
            """)
    List<Invoice> findOpenDueOn(@Param("dueDate") LocalDate dueDate);

    /**
     * Live rent invoices of a resident for one month, across all of their allocations - except invoices for a period
     * their stay never reached (checked out on/before the period start: credited back at move-out).
     */
    @Query("""
            select i from Invoice i
            where i.tenantId = :tenantId and i.invoiceType = com.pgms.billing.InvoiceType.RENT
              and i.billingMonth = :billingMonth and i.status <> com.pgms.billing.InvoiceStatus.VOID
              and not exists (select 1 from Allocation a where a.id = i.allocationId
                              and a.endReason = com.pgms.booking.EndReason.CHECK_OUT
                              and i.periodStart is not null and a.endDate <= i.periodStart)
            order by i.periodStart
            """)
    List<Invoice> findLiveRentForTenantMonth(@Param("tenantId") Long tenantId, @Param("billingMonth") LocalDate billingMonth);

    /** Live rent invoice ids whose period reaches the move-out date or later (credit candidates at move-out). */
    @Query("""
            select i.id from Invoice i
            where i.tenantId = :tenantId and i.invoiceType = com.pgms.billing.InvoiceType.RENT
              and i.status <> com.pgms.billing.InvoiceStatus.VOID and i.periodEnd >= :date
            order by i.id
            """)
    List<Long> findLiveRentIdsEndingOnOrAfter(@Param("tenantId") Long tenantId, @Param("date") LocalDate date);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select i from Invoice i
            where i.allocationId = :allocationId and i.invoiceType = com.pgms.billing.InvoiceType.SETTLEMENT
              and i.status <> com.pgms.billing.InvoiceStatus.VOID
            """)
    Optional<Invoice> findLiveSettlementForUpdate(@Param("allocationId") Long allocationId);

    @Query("""
            select count(i) > 0 from Invoice i
            where i.allocationId = :allocationId and i.invoiceType = com.pgms.billing.InvoiceType.SECURITY_DEPOSIT
              and i.status in (com.pgms.billing.InvoiceStatus.ISSUED, com.pgms.billing.InvoiceStatus.PARTIALLY_PAID,
                com.pgms.billing.InvoiceStatus.OVERDUE)
            """)
    boolean hasOpenDepositInvoice(@Param("allocationId") Long allocationId);

    /**
     * Whether the resident currently occupies a bed. Late fees stop after the move-out: the settlement already applied
     * the deposit and fixed the amount still payable.
     */
    @Query("""
            select count(a) > 0 from Allocation a
            where a.tenantId = :tenantId and a.status = com.pgms.booking.AllocationStatus.ACTIVE
            """)
    boolean tenantHasActiveStay(@Param("tenantId") Long tenantId);
}
