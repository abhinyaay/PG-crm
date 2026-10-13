package com.pgms.booking;

import com.pgms.billing.BillingDtos.SettlementSummary;
import com.pgms.billing.InvoiceService.SettlementCharge;
import com.pgms.tenant.Tenant;
import java.time.LocalDate;
import java.util.List;

/**
 * Settles the money side of a check-out inside the check-out transaction (implemented by the move-out module):
 * credits rent for days not stayed, posts deductions as a SETTLEMENT invoice, applies the stay's deposit to the open
 * dues and records what is left to refund or collect. Called after the allocation has ended and invoices for periods
 * after the move-out were closed.
 */
public interface CheckOutSettlement {

    /**
     * @param moveOutId the move-out case being completed, or null for the legacy check-out endpoint (which then uses
     *                  the resident's open case if any, or opens a settled one; notice/lock-in charges are not applied)
     * @param charges   deductions to post (inspection items are added by the implementation for a move-out case)
     */
    SettlementSummary settle(Tenant tenant, Allocation allocation, LocalDate checkOutDate, List<SettlementCharge> charges,
                             Long moveOutId);
}
