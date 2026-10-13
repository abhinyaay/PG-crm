package com.pgms.moveout;

import com.pgms.audit.AuditService;
import com.pgms.billing.BillingDtos.Deduction;
import com.pgms.billing.BillingDtos.SettlementSummary;
import com.pgms.billing.Invoice;
import com.pgms.billing.InvoiceService;
import com.pgms.billing.InvoiceService.RentCredit;
import com.pgms.billing.InvoiceService.SettlementCharge;
import com.pgms.booking.Allocation;
import com.pgms.booking.CheckOutSettlement;
import com.pgms.common.exception.NotFoundException;
import com.pgms.deposit.DepositAccount;
import com.pgms.deposit.DepositOperations;
import com.pgms.deposit.DepositOperations.SettlementOutcome;
import com.pgms.moveout.MoveOutEnums.Initiator;
import com.pgms.moveout.MoveOutEnums.Reason;
import com.pgms.moveout.MoveOutPolicy.FinalMonthBilling;
import com.pgms.security.CurrentUserProvider;
import com.pgms.tenant.Tenant;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists the settlement of a check-out (both the move-out workflow and the legacy check-out endpoint):
 * <ol>
 *   <li>credit rent for days not stayed (final month per policy, months starting after the move-out);</li>
 *   <li>post the deductions (notice shortfall, lock-in penalty, inspection, extra) on the SETTLEMENT invoice;</li>
 *   <li>apply this stay's deposit - and the rent credit - to the resident's open invoices, oldest first, as internal
 *       DEPOSIT_ADJUSTMENT payments, so the dues counted against the deposit are actually closed;</li>
 *   <li>record refund due (by the policy's refund SLA) or the amount still payable on the move-out case.</li>
 * </ol>
 */
@Service
public class MoveOutSettlementService implements CheckOutSettlement {

    private final MoveOutRepository moveOutRepository;
    private final MoveOutPolicyService policyService;
    private final MoveOutSupport support;
    private final InvoiceService invoiceService;
    private final DepositOperations depositOperations;
    private final CurrentUserProvider currentUserProvider;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public MoveOutSettlementService(MoveOutRepository moveOutRepository, MoveOutPolicyService policyService,
                                    MoveOutSupport support, InvoiceService invoiceService, DepositOperations depositOperations,
                                    CurrentUserProvider currentUserProvider, AuditService auditService,
                                    ApplicationEventPublisher events, Clock clock) {
        this.moveOutRepository = moveOutRepository;
        this.policyService = policyService;
        this.support = support;
        this.invoiceService = invoiceService;
        this.depositOperations = depositOperations;
        this.currentUserProvider = currentUserProvider;
        this.auditService = auditService;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public SettlementSummary settle(Tenant tenant, Allocation allocation, LocalDate checkOutDate, List<SettlementCharge> charges,
                                    Long moveOutId) {
        MoveOut moveOut = moveOutId != null
                ? moveOutRepository.findByIdForUpdate(moveOutId).orElseThrow(() -> new NotFoundException("Move-out", moveOutId))
                : legacyCase(tenant, allocation, checkOutDate);
        MoveOutPolicy policy = policyService.effective(allocation.getPropertyId());
        Instant now = clock.instant();

        List<RentCredit> credits = invoiceService.creditUnusedRent(tenant.getId(), checkOutDate,
                policy.getFinalMonthBilling() == FinalMonthBilling.PRORATED);
        Invoice settlementInvoice = invoiceService.addSettlementCharges(allocation, charges);
        List<Invoice> open = invoiceService.lockOpenInvoices(tenant.getId());
        BigDecimal chargesTotal = charges.stream().map(SettlementCharge::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal openTotal = open.stream().map(Invoice::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal outstandingDues = openTotal.subtract(chargesTotal).max(BigDecimal.ZERO);

        DepositAccount account = depositOperations.ensureAccount(allocation);
        SettlementOutcome outcome = depositOperations.settle(account, moveOut.getId(), checkOutDate, credits, open,
                policy.getDepositRefundDays());

        moveOut.settle(new MoveOut.Settlement(checkOutDate, settlementInvoice == null ? null : settlementInvoice.getId(),
                account.getId(), outcome.depositHeld(), outstandingDues, outcome.duesAdjusted(), chargesTotal,
                outcome.rentCredit(), outcome.refundDue(), outcome.payableByTenant(), outcome.refundDueBy()), now);
        if (outcome.refundDue().signum() == 0 && outcome.payableByTenant().signum() == 0) {
            moveOut.close("Nothing left to refund or collect", now);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("depositHeld", outcome.depositHeld());
        details.put("outstandingDues", outstandingDues);
        details.put("deductions", chargesTotal);
        details.put("duesAdjusted", outcome.duesAdjusted());
        details.put("rentCredit", outcome.rentCredit());
        details.put("refundDue", outcome.refundDue());
        details.put("payableByTenant", outcome.payableByTenant());
        auditService.record(moveOut.getOrganizationId(), "MOVE_OUT_SETTLED", "MOVE_OUT", moveOut.getId(), details);
        events.publishEvent(new MoveOutEvents.SettlementFinalized(moveOut.getId(), moveOut.getReference(),
                moveOut.getOrganizationId(), moveOut.getPropertyId(), moveOut.getTenantId(), outcome.depositHeld(), chargesTotal,
                outcome.refundDue(), outcome.refundDueBy(), outcome.payableByTenant()));

        List<Deduction> items = charges.stream().map(c -> new Deduction(c.description(), c.amount())).toList();
        return new SettlementSummary(outcome.depositHeld(), outstandingDues, chargesTotal, outcome.refundDue(),
                outcome.payableByTenant(), items, outcome.duesAdjusted(), outcome.rentCredit(), outcome.refundDueBy(),
                moveOut.getId(), settlementInvoice == null ? null : settlementInvoice.getId(), account.getId());
    }

    /**
     * Legacy check-out: the resident's open case (opened by a notice) or a new one. Notice-shortfall and lock-in
     * charges are not applied on this path, so the case records them as waived.
     */
    private MoveOut legacyCase(Tenant tenant, Allocation allocation, LocalDate checkOutDate) {
        MoveOut moveOut = moveOutRepository.findOpenForTenantForUpdate(tenant.getId()).orElseGet(() -> {
            LocalDate noticeDate = allocation.getNoticeDate() == null || allocation.getNoticeDate().isAfter(checkOutDate)
                    ? checkOutDate : allocation.getNoticeDate();
            return moveOutRepository.saveAndFlush(new MoveOut(support.nextReference(), allocation.getOrganizationId(),
                    allocation.getPropertyId(), tenant.getId(), allocation.getId(), currentUserProvider.currentUserId().orElse(null),
                    Initiator.STAFF, noticeDate, checkOutDate, Reason.OTHER, "Checked out via the allocation check-out", null));
        });
        moveOut.waiveCharges();
        return moveOut;
    }
}
