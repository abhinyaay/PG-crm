package com.pgms.billing.autopay;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AutopayAttemptRepository extends JpaRepository<AutopayAttempt, Long>, JpaSpecificationExecutor<AutopayAttempt> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AutopayAttempt a where a.id = :id")
    Optional<AutopayAttempt> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AutopayAttempt a where a.provider = :provider and a.providerPaymentId = :paymentId")
    Optional<AutopayAttempt> findByProviderPaymentIdForUpdate(@Param("provider") String provider,
                                                              @Param("paymentId") String providerPaymentId);

    List<AutopayAttempt> findByInvoiceIdOrderByAttemptNo(Long invoiceId);

    @Query("""
            select a.id from AutopayAttempt a
            where a.status = com.pgms.billing.autopay.AttemptStatus.SCHEDULED and a.attemptDate < :before
            order by a.id
            """)
    List<Long> findUnconfirmedBefore(@Param("before") LocalDate before);
}
