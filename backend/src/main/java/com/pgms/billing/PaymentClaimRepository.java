package com.pgms.billing;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentClaimRepository extends JpaRepository<PaymentClaim, Long>, JpaSpecificationExecutor<PaymentClaim> {

    /** Lock order for approval: claim, then (inside the payment processor) the invoice. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PaymentClaim c where c.id = :id")
    Optional<PaymentClaim> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select count(c) > 0 from PaymentClaim c
            where c.organizationId = :organizationId and upper(c.utr) = upper(:utr)
              and c.status in (com.pgms.billing.PaymentClaimStatus.SUBMITTED, com.pgms.billing.PaymentClaimStatus.APPROVED)
            """)
    boolean existsLiveUtr(@Param("organizationId") Long organizationId, @Param("utr") String utr);

    /** SUBMITTED claims older than {@code before} (pending-claims digest). */
    @Query("""
            select c from PaymentClaim c
            where c.status = com.pgms.billing.PaymentClaimStatus.SUBMITTED and c.createdAt < :before
            order by c.propertyId, c.id
            """)
    List<PaymentClaim> findPendingSubmittedBefore(@Param("before") Instant before);
}
