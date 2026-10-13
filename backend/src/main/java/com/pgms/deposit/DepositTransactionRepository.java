package com.pgms.deposit;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DepositTransactionRepository extends JpaRepository<DepositTransaction, Long> {

    Optional<DepositTransaction> findByOrganizationIdAndIdempotencyKey(Long organizationId, String idempotencyKey);

    List<DepositTransaction> findByDepositAccountIdOrderByCreatedAtAscIdAsc(Long depositAccountId);

    boolean existsByRefundId(Long refundId);

    /** Rent credit not yet applied or paid out, oldest first. */
    @Query("""
            select t from DepositTransaction t
            where t.depositAccountId = :accountId and t.type = com.pgms.deposit.DepositTransactionType.RENT_CREDIT
              and t.remaining > 0
            order by t.id
            """)
    List<DepositTransaction> findOpenCredits(@Param("accountId") Long accountId);
}
