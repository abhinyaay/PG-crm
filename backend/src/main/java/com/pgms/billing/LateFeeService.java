package com.pgms.billing;

import com.pgms.audit.AuditService;
import com.pgms.billing.BillingDtos.InvoiceResponse;
import com.pgms.billing.CollectionsDtos.LateFeeEntryResponse;
import com.pgms.billing.CollectionsDtos.LateFeeHistoryResponse;
import com.pgms.billing.CollectionsDtos.WaiveLateFeeRequest;
import com.pgms.common.exception.BusinessRuleException;
import com.pgms.common.exception.NotFoundException;
import com.pgms.security.CurrentUserProvider;
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

/** Automatic late fees (one invoice per transaction, under the invoice row lock), manager waivers and the fee history. */
@Service
public class LateFeeService {

    public enum Outcome { APPLIED, SKIPPED }

    private final InvoiceRepository invoiceRepository;
    private final LateFeeEntryRepository entryRepository;
    private final BillingPolicyService policyService;
    private final InvoiceService invoiceService;
    private final CurrentUserProvider currentUserProvider;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public LateFeeService(InvoiceRepository invoiceRepository, LateFeeEntryRepository entryRepository,
                          BillingPolicyService policyService, InvoiceService invoiceService,
                          CurrentUserProvider currentUserProvider, AuditService auditService, ApplicationEventPublisher events,
                          Clock clock) {
        this.invoiceRepository = invoiceRepository;
        this.entryRepository = entryRepository;
        this.policyService = policyService;
        this.invoiceService = invoiceService;
        this.currentUserProvider = currentUserProvider;
        this.auditService = auditService;
        this.events = events;
        this.clock = clock;
    }

    /** A rent invoice whose period starts after the 1st of its month is the prorated move-in invoice. */
    static boolean isMoveIn(Invoice invoice) {
        return invoice.getPeriodStart() != null && invoice.getBillingMonth() != null
                && !invoice.getPeriodStart().equals(invoice.getBillingMonth());
    }

    /**
     * Charges whatever late fee is owed today and not yet charged. The invoice is locked first, so the job serialises
     * with payments: a payment that cleared the balance (status no longer OVERDUE) stops further fees. A concurrent
     * duplicate run trips {@code uk_late_fee_invoice_period}; the caller counts that as skipped.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome applyLateFee(Long invoiceId, LocalDate today) {
        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId).orElse(null);
        if (invoice == null || invoice.getInvoiceType() != InvoiceType.RENT || invoice.getStatus() != InvoiceStatus.OVERDUE
                || invoice.balance().signum() <= 0) {
            return Outcome.SKIPPED;
        }
        BillingPolicy policy = policyService.effectiveFor(invoice.getPropertyId());
        LateFeeCalculator.Assessment assessment = LateFeeCalculator.assess(policy, facts(invoice), today);
        if (!assessment.charges()) {
            return Outcome.SKIPPED;
        }
        Instant now = clock.instant();
        InvoiceLineItem line = invoice.addCharge(LineItemType.LATE_FEE, "Late fee (" + assessment.periodKey() + ")",
                assessment.amount(), now, null, today);
        invoiceRepository.flush();
        entryRepository.saveAndFlush(LateFeeEntry.charge(invoice, line.getId(), policy.lateFeeType(), assessment.periodKey(),
                assessment.amount(), now));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("amount", assessment.amount());
        details.put("periodKey", assessment.periodKey().toString());
        details.put("rule", policy.lateFeeType().name());
        auditService.record(invoice.getOrganizationId(), "LATE_FEE_APPLIED", "INVOICE", invoiceId, details);
        events.publishEvent(new BillingEvents.LateFeeApplied(invoiceId, invoice.getInvoiceNumber(), invoice.getOrganizationId(),
                invoice.getPropertyId(), invoice.getTenantId(), assessment.amount(), invoice.balance()));
        return Outcome.APPLIED;
    }

    @Transactional
    public InvoiceResponse waive(Long invoiceId, WaiveLateFeeRequest request) {
        Invoice invoice = invoiceService.lockAccessible(invoiceId);
        BigDecimal charged = entryRepository.sumByInvoiceAndKind(invoiceId, LateFeeEntryKind.CHARGE);
        BigDecimal waived = entryRepository.sumByInvoiceAndKind(invoiceId, LateFeeEntryKind.WAIVER);
        BigDecimal waivable = charged.subtract(waived);
        if (request.amount().compareTo(waivable) > 0) {
            throw new BusinessRuleException("WAIVER_EXCEEDS_LATE_FEES",
                    "Only " + waivable + " of late fees can still be waived on this invoice");
        }
        Instant now = clock.instant();
        Long userId = currentUserProvider.currentUserId().orElse(null);
        String reason = request.reason().trim();
        InvoiceLineItem line = invoice.applyDiscount("Late fee waiver: " + reason, request.amount(), now, userId,
                LocalDate.now(clock));
        invoiceRepository.flush();
        entryRepository.saveAndFlush(LateFeeEntry.waiver(invoice, line.getId(), request.amount(), reason, now, userId));
        auditService.record(invoice.getOrganizationId(), "LATE_FEE_WAIVED", "INVOICE", invoiceId,
                Map.of("amount", request.amount(), "reason", reason));
        events.publishEvent(new BillingEvents.LateFeeWaived(invoiceId, invoice.getInvoiceNumber(), invoice.getOrganizationId(),
                invoice.getPropertyId(), invoice.getTenantId(), request.amount(), invoice.balance(), reason));
        return InvoiceResponse.of(invoice, true);
    }

    @Transactional(readOnly = true)
    public LateFeeHistoryResponse history(Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId).orElseThrow(() -> new NotFoundException("Invoice", invoiceId));
        invoiceService.requireAccess(invoice);
        List<LateFeeEntry> entries = entryRepository.findByInvoiceIdOrderByIdAsc(invoiceId);
        BigDecimal charged = sum(entries, LateFeeEntryKind.CHARGE);
        BigDecimal waived = sum(entries, LateFeeEntryKind.WAIVER);
        return new LateFeeHistoryResponse(invoiceId, charged, waived, charged.subtract(waived), lateFeeFrom(invoice),
                entries.stream().map(LateFeeEntryResponse::of).toList());
    }

    /** First day a late fee applies to this invoice, or null when none will be charged under the current policy. */
    @Transactional(readOnly = true)
    public LocalDate lateFeeFrom(Invoice invoice) {
        return lateFeeFrom(invoice, policyService.effectiveFor(invoice.getPropertyId()));
    }

    static LocalDate lateFeeFrom(Invoice invoice, BillingPolicy policy) {
        if (invoice.getInvoiceType() != InvoiceType.RENT || !policy.lateFeeEnabled()
                || (isMoveIn(invoice) && !policy.lateFeeOnMoveIn())) {
            return null;
        }
        return LateFeeCalculator.firstLateDay(policy, invoice.getIssueDate(), invoice.getDueDate());
    }

    private LateFeeCalculator.InvoiceFacts facts(Invoice invoice) {
        return new LateFeeCalculator.InvoiceFacts(invoice.getIssueDate(), invoice.getDueDate(), isMoveIn(invoice),
                invoice.sumOf(LineItemType.RENT), entryRepository.sumByInvoiceAndKind(invoice.getId(), LateFeeEntryKind.CHARGE),
                entryRepository.existsByInvoiceIdAndKind(invoice.getId(), LateFeeEntryKind.CHARGE));
    }

    private static BigDecimal sum(List<LateFeeEntry> entries, LateFeeEntryKind kind) {
        return entries.stream().filter(e -> e.getKind() == kind).map(LateFeeEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
