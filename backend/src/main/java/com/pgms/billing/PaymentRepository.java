package com.pgms.billing;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long>, JpaSpecificationExecutor<Payment> {

    Optional<Payment> findByOrganizationIdAndIdempotencyKey(Long organizationId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") Long id);

    List<Payment> findByInvoiceIdOrderByPaidAt(Long invoiceId);

    /** Deposit currently held for a tenant: deposit payments net of refunds. */
    @Query("""
            select coalesce(sum(p.amount - p.refundedAmount), 0) from Payment p, Invoice i
            where p.invoiceId = i.id and p.tenantId = :tenantId
              and i.invoiceType = com.pgms.billing.InvoiceType.SECURITY_DEPOSIT
            """)
    BigDecimal depositHeldForTenant(@Param("tenantId") Long tenantId);
}
