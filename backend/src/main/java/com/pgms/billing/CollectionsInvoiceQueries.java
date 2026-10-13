package com.pgms.billing;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Read-only invoice queries of the collections jobs (kept apart from {@link InvoiceRepository}). */
public interface CollectionsInvoiceQueries extends Repository<Invoice, Long> {

    /** Not yet overdue open invoices due in [from, to] (T-N reminders). */
    @Query("""
            select i from Invoice i
            where i.status in (com.pgms.billing.InvoiceStatus.ISSUED, com.pgms.billing.InvoiceStatus.PARTIALLY_PAID)
              and i.dueDate between :from and :to and i.totalAmount > i.amountPaid
            order by i.id
            """)
    List<Invoice> findOpenDueBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Overdue invoices with a balance whose due date is on/after {@code dueFrom} (D+n reminders). */
    @Query("""
            select i from Invoice i
            where i.status = com.pgms.billing.InvoiceStatus.OVERDUE and i.totalAmount > i.amountPaid
              and i.dueDate >= :dueFrom and i.dueDate < :today
            order by i.id
            """)
    List<Invoice> findOverdueDueBetween(@Param("dueFrom") LocalDate dueFrom, @Param("today") LocalDate today);

    /** Every overdue invoice with a balance, grouped by property for the digest. */
    @Query("""
            select i from Invoice i
            where i.status = com.pgms.billing.InvoiceStatus.OVERDUE and i.totalAmount > i.amountPaid
            order by i.propertyId, i.id
            """)
    List<Invoice> findAllOverdue();
}
