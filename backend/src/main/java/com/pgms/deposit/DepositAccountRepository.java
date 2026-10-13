package com.pgms.deposit;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DepositAccountRepository extends JpaRepository<DepositAccount, Long> {

    Optional<DepositAccount> findByRootAllocationId(Long rootAllocationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from DepositAccount a where a.id = :id")
    Optional<DepositAccount> findByIdForUpdate(@Param("id") Long id);

    @Query("select a.tenantId from DepositAccount a where a.id = :id")
    Optional<Long> findTenantIdById(@Param("id") Long id);

    List<DepositAccount> findByTenantIdOrderByIdDesc(Long tenantId);

    /** Refunds past their due-by date that were not yet reported today. */
    @Query("""
            select a.id from DepositAccount a
            where a.status in (com.pgms.deposit.DepositStatus.REFUND_DUE, com.pgms.deposit.DepositStatus.PARTIALLY_REFUNDED)
              and a.refundDueBy < :today and (a.overdueNotifiedOn is null or a.overdueNotifiedOn < :today)
            order by a.id
            """)
    List<Long> findIdsWithOverdueRefund(@Param("today") LocalDate today);
}
