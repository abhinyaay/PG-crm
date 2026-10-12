package com.pgms.booking;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AllocationRepository extends JpaRepository<Allocation, Long>, JpaSpecificationExecutor<Allocation> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Allocation a where a.id = :id")
    Optional<Allocation> findByIdForUpdate(@Param("id") Long id);

    @Query("select a.tenantId from Allocation a where a.id = :id")
    Optional<Long> findTenantIdById(@Param("id") Long id);

    Optional<Allocation> findByTenantIdAndStatus(Long tenantId, AllocationStatus status);

    Optional<Allocation> findByBedIdAndStatus(Long bedId, AllocationStatus status);

    List<Allocation> findByTenantIdOrderByStartDateDesc(Long tenantId);

    List<Allocation> findByBedIdOrderByStartDateDesc(Long bedId);

    /**
     * Allocations that occupied a bed on the first day of the month whose tenant has no live rent invoice for it yet.
     * Selected by date range rather than status so a delayed job run still bills a tenant who left mid-month.
     * The "already billed" check is per <em>tenant</em>, not per allocation: a transfer effective on (or back-dated
     * before) the 1st after that month was billed on the old allocation keeps the month there - the new allocation
     * must not be billed for it a second time.
     */
    @Query("""
            select a.id from Allocation a
            where a.startDate <= :monthStart and (a.endDate is null or a.endDate > :monthStart)
              and not exists (select 1 from Invoice i where i.tenantId = a.tenantId
                              and i.invoiceType = com.pgms.billing.InvoiceType.RENT
                              and i.billingMonth = :monthStart
                              and i.status <> com.pgms.billing.InvoiceStatus.VOID)
            order by a.id
            """)
    List<Long> findUnbilledForMonth(@Param("monthStart") LocalDate monthStart);
}
