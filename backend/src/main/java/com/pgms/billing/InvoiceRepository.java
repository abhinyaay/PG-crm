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
}
