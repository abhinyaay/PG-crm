package com.pgms.billing;

import com.pgms.audit.AuditService;
import com.pgms.billing.BillingDtos.AddChargeRequest;
import com.pgms.billing.BillingDtos.InvoiceResponse;
import com.pgms.billing.BillingDtos.TenantBalanceResponse;
import com.pgms.booking.Allocation;
import com.pgms.booking.AllocationRepository;
import com.pgms.common.api.PageResponse;
import com.pgms.common.api.SortValidator;
import com.pgms.common.exception.ConflictException;
import com.pgms.common.exception.NotFoundException;
import com.pgms.common.lookup.DisplayNames;
import com.pgms.property.Property;
import com.pgms.property.PropertyRepository;
import com.pgms.security.AccessGuard;
import com.pgms.security.AccessScope;
import com.pgms.security.CurrentUserProvider;
import com.pgms.tenant.TenantService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvoiceService {

    private static final Set<String> SORTABLE = Set.of("dueDate", "issueDate", "billingMonth", "totalAmount", "createdAt");
    private static final Set<InvoiceStatus> OPEN = EnumSet.of(InvoiceStatus.ISSUED, InvoiceStatus.PARTIALLY_PAID,
            InvoiceStatus.OVERDUE);

    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final AllocationRepository allocationRepository;
    private final PropertyRepository propertyRepository;
    private final InvoiceFactory invoiceFactory;
    private final TenantService tenantService;
    private final AccessGuard accessGuard;
    private final CurrentUserProvider currentUserProvider;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final DisplayNames displayNames;
    private final BillingPolicyService billingPolicyService;
    private final RecurringChargeService recurringChargeService;
    private final Clock clock;

    public InvoiceService(InvoiceRepository invoiceRepository, PaymentRepository paymentRepository,
                          AllocationRepository allocationRepository, PropertyRepository propertyRepository,
                          InvoiceFactory invoiceFactory, TenantService tenantService, AccessGuard accessGuard,
                          CurrentUserProvider currentUserProvider, AuditService auditService,
                          ApplicationEventPublisher events, DisplayNames displayNames,
                          BillingPolicyService billingPolicyService,
                          RecurringChargeService recurringChargeService, Clock clock) {
        this.invoiceRepository = invoiceRepository;
        this.paymentRepository = paymentRepository;
        this.allocationRepository = allocationRepository;
        this.propertyRepository = propertyRepository;
        this.invoiceFactory = invoiceFactory;
        this.tenantService = tenantService;
        this.accessGuard = accessGuard;
        this.currentUserProvider = currentUserProvider;
        this.auditService = auditService;
        this.events = events;
        this.displayNames = displayNames;
        this.billingPolicyService = billingPolicyService;
        this.recurringChargeService = recurringChargeService;
        this.clock = clock;
    }

    /**
     * Called inside the check-in transaction: deposit + prorated first-month rent, atomically with the allocation.
     * A back-dated check-in is also billed for every later month up to the current one: the daily job only bills the
     * current month, so a stay back-dated across two month boundaries (e.g. Jan 30 recorded on Mar 1) would otherwise
     * never be billed for the month in between.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Invoice> issueMoveInInvoices(Allocation allocation, int rentDueDay) {
        LocalDate today = LocalDate.now(clock);
        Long userId = currentUserProvider.currentUserId().orElse(null);
        List<Invoice> issued = new ArrayList<>();
        invoiceFactory.depositInvoice(allocation, allocation.getStartDate(), userId).ifPresent(issued::add);
        YearMonth current = YearMonth.from(today);
        for (YearMonth month = YearMonth.from(allocation.getStartDate()); !month.isAfter(current); month = month.plusMonths(1)) {
            LocalDate from = firstUnbilledDay(allocation, month);
            if (from == null) {
                continue;
            }
            YearMonth billed = month;
            invoiceFactory.rentInvoice(allocation, month, rentDueDay, today, userId, from).ifPresent(invoice -> {
                recurringChargeService.addRecurringLines(invoice, billed, userId);
                issued.add(invoice);
            });
        }
        invoiceRepository.saveAll(issued);
        issued.forEach(this::publishIssued);
        return issued;
    }

    /**
     * First day of {@code month} (from the allocation start) not yet covered by a live rent invoice of the same
     * resident, or null when the rest of the month is already billed. A resident who checks out and back in within
     * one month already has that month's rent invoice from the earlier stay (the daily job applies the same per-tenant
     * check), so the new stay is billed only for days the earlier invoice does not cover. The caller holds the tenant
     * row lock, so two check-ins cannot both see the month as unbilled.
     */
    private LocalDate firstUnbilledDay(Allocation allocation, YearMonth month) {
        LocalDate from = allocation.getStartDate().isAfter(month.atDay(1)) ? allocation.getStartDate() : month.atDay(1);
        for (Invoice existing : invoiceRepository.findLiveRentForTenantMonth(allocation.getTenantId(), month.atDay(1))) {
            LocalDate start = existing.getPeriodStart() == null ? month.atDay(1) : existing.getPeriodStart();
            LocalDate end = existing.getPeriodEnd() == null ? month.atEndOfMonth() : existing.getPeriodEnd();
            if (!start.isAfter(from) && !end.isBefore(from)) {
                from = end.plusDays(1);
            }
        }
        return from.isAfter(month.atEndOfMonth()) ? null : from;
    }

    /**
     * Generates one month's rent invoice for one allocation in its own transaction, so a single bad record cannot
     * abort the whole billing run. A concurrent duplicate is rejected by the uk_invoices_rent_month unique index;
     * the caller treats that as "already billed" (the exception must escape this method so the transaction rolls back
     * cleanly instead of failing at commit).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean generateRentInvoice(Long allocationId, YearMonth month) {
        Allocation allocation = allocationRepository.findById(allocationId)
                .orElseThrow(() -> new NotFoundException("Allocation", allocationId));
        Property property = propertyRepository.findById(allocation.getPropertyId())
                .orElseThrow(() -> new NotFoundException("Property", allocation.getPropertyId()));
        return invoiceFactory.rentInvoice(allocation, month, property.getRentDueDay(), LocalDate.now(clock), null)
                .map(invoice -> {
                    recurringChargeService.addRecurringLines(invoice, month, null);
                    invoiceRepository.saveAndFlush(invoice);
                    publishIssued(invoice);
                    return true;
                })
                .orElse(false);
    }

    /** Flags one invoice overdue and queues the overdue notice in the same transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markOverdue(Long invoiceId, LocalDate today) {
        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId).orElse(null);
        if (invoice == null || !invoice.markOverdueIfDue(today)) {
            return false;
        }
        // same dedup key format (PAYMENT_OVERDUE:{id}:D{n}) as the D+n reminder job, so day 1 is never sent twice
        events.publishEvent(CollectionReminderService.overdueEvent(invoice,
                billingPolicyService.effectiveFor(invoice.getPropertyId()), today));
        return true;
    }

    @Transactional(readOnly = true)
    public InvoiceResponse get(Long id) {
        Invoice invoice = invoiceRepository.findWithLines(id).orElseThrow(() -> new NotFoundException("Invoice", id));
        requireAccess(invoice);
        return InvoiceResponse.of(invoice, true, tenantName(invoice));
    }

    @Transactional(readOnly = true)
    public PageResponse<InvoiceResponse> search(Long propertyId, Long tenantId, InvoiceStatus status, InvoiceType type,
                                                YearMonth billingMonth, LocalDate dueBefore, Pageable pageable) {
        Pageable page = SortValidator.validate(pageable, SORTABLE, Sort.by(Sort.Direction.DESC, "dueDate"));
        Specification<Invoice> spec;
        if (accessGuard.isTenant()) {
            Long ownId = accessGuard.requireCurrentTenant().getId();
            spec = (root, q, cb) -> cb.equal(root.get("tenantId"), ownId);
        } else {
            AccessScope scope = accessGuard.scopeFor(propertyId);
            if (scope.isEmpty()) {
                return PageResponse.from(Page.<Invoice>empty(page), i -> InvoiceResponse.of(i, false, null));
            }
            spec = scope.toSpecification();
            if (tenantId != null) {
                spec = spec.and((root, q, cb) -> cb.equal(root.get("tenantId"), tenantId));
            }
        }
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        if (type != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("invoiceType"), type));
        }
        if (billingMonth != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("billingMonth"), billingMonth.atDay(1)));
        }
        if (dueBefore != null) {
            spec = spec.and((root, q, cb) -> cb.lessThan(root.get("dueDate"), dueBefore));
        }
        Page<Invoice> invoices = invoiceRepository.findAll(spec, page);
        DisplayNames.Index names = displayNames.tenants(DisplayNames.ids(invoices.getContent(), Invoice::getTenantId));
        return PageResponse.from(invoices, i -> InvoiceResponse.of(i, false, names.tenant(i.getTenantId())));
    }

    @Transactional
    public InvoiceResponse addCharge(Long id, AddChargeRequest request) {
        Invoice invoice = lockAccessible(id);
        invoice.addCharge(request.type(), request.description(), request.amount(), clock.instant(),
                currentUserProvider.currentUserId().orElse(null), LocalDate.now(clock));
        auditService.record(invoice.getOrganizationId(), "INVOICE_CHARGE_ADDED", "INVOICE", id,
                Map.of("type", request.type().name(), "amount", request.amount()));
        return InvoiceResponse.of(invoice, true, tenantName(invoice));
    }

    @Transactional
    public InvoiceResponse applyDiscount(Long id, String reason, BigDecimal amount) {
        Invoice invoice = lockAccessible(id);
        invoice.applyDiscount(reason, amount, clock.instant(), currentUserProvider.currentUserId().orElse(null),
                LocalDate.now(clock));
        auditService.record(invoice.getOrganizationId(), "INVOICE_DISCOUNT_APPLIED", "INVOICE", id,
                Map.of("amount", amount, "reason", reason));
        return InvoiceResponse.of(invoice, true, tenantName(invoice));
    }

    @Transactional
    public InvoiceResponse voidInvoice(Long id, String reason) {
        Invoice invoice = lockAccessible(id);
        invoice.voidInvoice(reason, clock.instant());
        auditService.record(invoice.getOrganizationId(), "INVOICE_VOIDED", "INVOICE", id, Map.of("reason", reason));
        return InvoiceResponse.of(invoice, true, tenantName(invoice));
    }

    @Transactional(readOnly = true)
    public TenantBalanceResponse tenantBalance(Long tenantId) {
        tenantService.loadAccessible(tenantId);
        String tenantName = displayNames.tenants(List.of(tenantId)).tenant(tenantId);
        List<InvoiceResponse> open = invoiceRepository.findByTenantIdAndStatusInOrderByDueDate(tenantId, OPEN).stream()
                .map(i -> InvoiceResponse.of(i, false, tenantName)).toList();
        return new TenantBalanceResponse(tenantId, invoiceRepository.outstandingForTenant(tenantId),
                paymentRepository.depositHeldForTenant(tenantId), open);
    }

    /** Display name for responses (also used by other billing services). */
    String tenantName(Invoice invoice) {
        return displayNames.tenants(List.of(invoice.getTenantId())).tenant(invoice.getTenantId());
    }

    /**
     * Called inside the check-out transaction, before {@link #settlement}. After move-out the tenant no longer owes
     * (a) rent for periods starting on or after the move-out date - typically next month's rent that the 00:30 job
     * billed on the 1st before a check-out effective that day was recorded, or any month after a back-dated
     * check-out - and (b) the uncollected part of the security deposit. Unpaid invoices are voided; partially paid
     * ones get their remaining balance written off, so the settlement counts only dues for the time actually stayed.
     * Invoices are locked one by one (ids read as scalars first, so no stale entity is served from the persistence
     * context); payments lock the same rows, so a concurrent payment cannot be lost.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int closeOutAfterMoveOut(Long tenantId, LocalDate moveOutDate) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock);
        Long userId = currentUserProvider.currentUserId().orElse(null);
        int adjusted = 0;
        for (Long id : invoiceRepository.findOpenIdsByTenant(tenantId)) {
            Invoice invoice = invoiceRepository.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Invoice", id));
            boolean notOwed = invoice.getInvoiceType() == InvoiceType.SECURITY_DEPOSIT
                    || invoice.getPeriodStart() != null && !invoice.getPeriodStart().isBefore(moveOutDate);
            if (!invoice.isOpen() || !notOwed) {
                continue;
            }
            String reason = invoice.getInvoiceType() == InvoiceType.SECURITY_DEPOSIT
                    ? "Uncollected deposit not due after check-out on " + moveOutDate
                    : "Not owed: period starts on/after check-out on " + moveOutDate;
            boolean voided = invoice.getAmountPaid().signum() == 0;
            if (voided) {
                invoice.voidInvoice(reason, now);
            } else {
                invoice.applyDiscount(reason, invoice.balance(), now, userId, today);
            }
            auditService.record(invoice.getOrganizationId(), voided ? "INVOICE_VOIDED" : "INVOICE_BALANCE_WRITTEN_OFF",
                    "INVOICE", id, Map.of("reason", reason));
            adjusted++;
        }
        invoiceRepository.flush();
        return adjusted;
    }

    /** One charge on a SETTLEMENT invoice. */
    public record SettlementCharge(LineItemType type, String description, BigDecimal amount) {
    }

    /**
     * Adds charges to the allocation's SETTLEMENT invoice, creating it (due today) on first use. Returns the live
     * settlement invoice (null when there is none and nothing to charge). A charge added to an already paid
     * settlement invoice reopens it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Invoice addSettlementCharges(Allocation allocation, List<SettlementCharge> charges) {
        List<SettlementCharge> positive = charges.stream().filter(c -> c.amount() != null && c.amount().signum() > 0).toList();
        Invoice invoice = invoiceRepository.findLiveSettlementForUpdate(allocation.getId()).orElse(null);
        if (positive.isEmpty()) {
            return invoice;
        }
        LocalDate today = LocalDate.now(clock);
        Long userId = currentUserProvider.currentUserId().orElse(null);
        boolean created = invoice == null;
        if (created) {
            invoice = invoiceFactory.settlementInvoice(allocation, today);
        }
        for (SettlementCharge charge : positive) {
            invoice.addCharge(charge.type(), charge.description(), charge.amount(), clock.instant(), userId, today);
        }
        invoiceRepository.saveAndFlush(invoice);
        if (created) {
            publishIssued(invoice);
        }
        auditService.record(invoice.getOrganizationId(), "SETTLEMENT_CHARGES_ADDED", "INVOICE", invoice.getId(),
                Map.of("charges", positive.size(), "total", positive.stream().map(SettlementCharge::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)));
        return invoice;
    }

    /** A further deposit invoice (top-up) on an existing stay; 409 while another deposit invoice is still open. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Invoice issueDepositTopUp(Allocation allocation, BigDecimal amount, String description) {
        if (invoiceRepository.hasOpenDepositInvoice(allocation.getId())) {
            throw new ConflictException("DEPOSIT_INVOICE_OPEN",
                    "This stay already has an unpaid deposit invoice; collect or void it before requesting a top-up");
        }
        Invoice invoice = invoiceFactory.depositTopUpInvoice(allocation, amount, description, LocalDate.now(clock),
                currentUserProvider.currentUserId().orElse(null));
        invoiceRepository.saveAndFlush(invoice);
        publishIssued(invoice);
        auditService.record(invoice.getOrganizationId(), "DEPOSIT_TOP_UP_INVOICED", "INVOICE", invoice.getId(),
                Map.of("amount", amount, "allocationId", allocation.getId()));
        return invoice;
    }

    /**
     * Rent credit for days not stayed. {@code discounted} was taken off the invoice balance; {@code refundable} was
     * already paid and has to be given back from that invoice's payments.
     */
    public record RentCredit(Long invoiceId, String invoiceNumber, BigDecimal discounted, BigDecimal refundable) {
    }

    /**
     * Credits rent for days not stayed, inside the check-out transaction after {@link #closeOutAfterMoveOut}:
     * <ul>
     *   <li>a rent invoice whose period starts on/after the move-out date (already voided or written off when unpaid)
     *       is credited with what was actually collected on it;</li>
     *   <li>with {@code prorateFinalMonth}, the invoice covering the move-out date is cut to end the day before and
     *       the unused days ({@code rent lines x unused days / billed days}) are discounted up to its balance; any
     *       remainder was already paid and becomes refundable.</li>
     * </ul>
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<RentCredit> creditUnusedRent(Long tenantId, LocalDate moveOutDate, boolean prorateFinalMonth) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock);
        Long userId = currentUserProvider.currentUserId().orElse(null);
        List<RentCredit> credits = new ArrayList<>();
        for (Long id : invoiceRepository.findLiveRentIdsEndingOnOrAfter(tenantId, moveOutDate)) {
            Invoice invoice = invoiceRepository.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Invoice", id));
            if (invoice.getPeriodStart() == null || invoice.getPeriodEnd() == null) {
                continue;
            }
            if (!invoice.getPeriodStart().isBefore(moveOutDate)) {
                BigDecimal collected = collectedNet(invoice.getId());
                if (collected.signum() > 0) {
                    credits.add(new RentCredit(invoice.getId(), invoice.getInvoiceNumber(), BigDecimal.ZERO, collected));
                }
                continue;
            }
            if (!prorateFinalMonth) {
                continue;
            }
            BigDecimal rentLines = invoice.getLineItems().stream().filter(l -> l.getLineType() == LineItemType.RENT)
                    .map(InvoiceLineItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal credit = RentCalculator.unusedRentCredit(rentLines, invoice.getPeriodStart(), invoice.getPeriodEnd(),
                    moveOutDate);
            invoice.endPeriodOn(moveOutDate.minusDays(1));
            if (credit.signum() <= 0) {
                continue;
            }
            BigDecimal discount = invoice.isOpen() ? credit.min(invoice.balance()) : BigDecimal.ZERO;
            if (discount.signum() > 0) {
                invoice.applyDiscount("Unused days from " + moveOutDate + " (final month prorated)", discount, now, userId,
                        today);
            }
            BigDecimal refundable = credit.subtract(discount).min(collectedNet(invoice.getId())).max(BigDecimal.ZERO);
            credits.add(new RentCredit(invoice.getId(), invoice.getInvoiceNumber(), discount, refundable));
            auditService.record(invoice.getOrganizationId(), "FINAL_MONTH_PRORATED", "INVOICE", id,
                    Map.of("moveOutDate", moveOutDate.toString(), "credit", credit, "discounted", discount));
        }
        invoiceRepository.flush();
        return credits;
    }

    /** Collected on an invoice by real payments (not deposit adjustments), net of refunds. */
    private BigDecimal collectedNet(Long invoiceId) {
        return paymentRepository.findByInvoiceIdOrderByPaidAt(invoiceId).stream()
                .filter(p -> p.getMethod() != PaymentMethod.DEPOSIT_ADJUSTMENT)
                .map(Payment::refundableAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Open invoice ids of a resident (all stays), ascending - the lock order for settlement. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Invoice> lockOpenInvoices(Long tenantId) {
        List<Invoice> open = new ArrayList<>();
        for (Long id : invoiceRepository.findOpenIdsByTenant(tenantId)) {
            invoiceRepository.findByIdForUpdate(id).filter(Invoice::isOpen).ifPresent(open::add);
        }
        return open;
    }

    Invoice lockAccessible(Long id) {
        Invoice invoice = invoiceRepository.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Invoice", id));
        accessGuard.requirePropertyAccess(invoice.getPropertyId());
        return invoice;
    }

    /** Tenants may only see their own invoices (404 otherwise); staff need access to the property. */
    void requireAccess(Invoice invoice) {
        if (accessGuard.isTenant()) {
            if (!invoice.getTenantId().equals(accessGuard.requireCurrentTenant().getId())) {
                throw new NotFoundException("Invoice", invoice.getId());
            }
            return;
        }
        accessGuard.requirePropertyAccess(invoice.getPropertyId());
    }

    private void publishIssued(Invoice invoice) {
        events.publishEvent(new BillingEvents.InvoiceIssued(invoice.getId(), invoice.getInvoiceNumber(),
                invoice.getOrganizationId(), invoice.getTenantId(), invoice.getInvoiceType(), invoice.getTotalAmount(),
                invoice.getDueDate()));
    }
}
