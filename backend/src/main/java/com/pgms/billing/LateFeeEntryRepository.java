package com.pgms.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LateFeeEntryRepository extends JpaRepository<LateFeeEntry, Long> {

    List<LateFeeEntry> findByInvoiceIdOrderByIdAsc(Long invoiceId);

    @Query("select coalesce(sum(e.amount), 0) from LateFeeEntry e where e.invoiceId = :invoiceId and e.kind = :kind")
    BigDecimal sumByInvoiceAndKind(@Param("invoiceId") Long invoiceId, @Param("kind") LateFeeEntryKind kind);

    boolean existsByInvoiceIdAndKind(Long invoiceId, LateFeeEntryKind kind);

    /**
     * RENT invoices that may owe a late fee today: overdue, with a balance, at a property whose policy enables late fees
     * (or without a stored policy when the configured default enables them).
     */
    @Query(value = """
            SELECT i.id FROM invoices i
            LEFT JOIN property_billing_policies p ON p.property_id = i.property_id
            WHERE i.status = 'OVERDUE' AND i.invoice_type = 'RENT' AND i.total_amount > i.amount_paid
              AND i.due_date < :today AND COALESCE(p.late_fee_enabled, :enabledByDefault)
            ORDER BY i.id
            """, nativeQuery = true)
    List<Long> findLateFeeCandidates(@Param("today") LocalDate today, @Param("enabledByDefault") boolean enabledByDefault);
}
