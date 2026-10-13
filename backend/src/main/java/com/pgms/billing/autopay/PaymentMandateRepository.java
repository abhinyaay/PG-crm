package com.pgms.billing.autopay;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentMandateRepository extends JpaRepository<PaymentMandate, Long>, JpaSpecificationExecutor<PaymentMandate> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from PaymentMandate m where m.id = :id")
    Optional<PaymentMandate> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from PaymentMandate m where m.provider = :provider and m.providerMandateId = :providerMandateId")
    Optional<PaymentMandate> findByProviderMandateIdForUpdate(@Param("provider") String provider,
                                                              @Param("providerMandateId") String providerMandateId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from PaymentMandate m where m.provider = :provider and m.providerTokenId = :tokenId")
    Optional<PaymentMandate> findByProviderTokenIdForUpdate(@Param("provider") String provider, @Param("tokenId") String tokenId);

    List<PaymentMandate> findByTenantIdOrderByIdDesc(Long tenantId);

    boolean existsByTenantIdAndStatusIn(Long tenantId, List<MandateStatus> statuses);

    /**
     * Open RENT invoices with a balance of residents holding an ACTIVE mandate: rows of {invoiceId, mandateId}. Due
     * date, offset, amount limit and attempt history are checked per row.
     */
    @Query("""
            select i.id, m.id from com.pgms.billing.Invoice i, PaymentMandate m
            where m.tenantId = i.tenantId and m.status = com.pgms.billing.autopay.MandateStatus.ACTIVE
              and i.invoiceType = com.pgms.billing.InvoiceType.RENT
              and i.status in (com.pgms.billing.InvoiceStatus.ISSUED, com.pgms.billing.InvoiceStatus.PARTIALLY_PAID,
                               com.pgms.billing.InvoiceStatus.OVERDUE)
              and i.totalAmount > i.amountPaid
            order by i.id
            """)
    List<Object[]> findDebitCandidates();
}
