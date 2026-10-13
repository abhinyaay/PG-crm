package com.pgms.billing;

import com.pgms.audit.AuditService;
import com.pgms.billing.BillingDtos.AddChargeRequest;
import com.pgms.billing.BillingDtos.Deduction;
import com.pgms.billing.BillingDtos.InvoiceResponse;
import com.pgms.billing.BillingDtos.SettlementSummary;
import com.pgms.billing.BillingDtos.TenantBalanceResponse;
import com.pgms.booking.Allocation;
import com.pgms.booking.AllocationRepository;
import com.pgms.common.api.PageResponse;
import com.pgms.common.api.SortValidator;
import com.pgms.common.exception.NotFoundException;
import com.pgms.common.lookup.DisplayNames;
import com.pgms.property.Property;
import com.pgms.property.PropertyRepository;
import com.pgms.security.AccessGuard;
import com.pgms.security.AccessScope;
import com.pgms.security.CurrentUserProvider;
import com.pgms.tenant.Tenant;
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
    private final Clock clock;

    public InvoiceService(InvoiceRepository invoiceRepository, PaymentRepository paymentRepository,
                          AllocationRepository allocationRepository, PropertyRepository propertyRepository,
                          InvoiceFactory invoiceFactory, TenantService tenantService, AccessGuard accessGuard,
                          CurrentUserProvider currentUserProvider, AuditService auditService,
                          ApplicationEventPublisher events, DisplayNames displayNames, Clock clock) {
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
            invoiceFactory.rentInvoice(allocation, month, rentDueDay, today, userId).ifPresent(issued::add);
        }
        invoiceRepository.saveAll(issued);
        issued.forEach(this::publishIssued);
        return issued;
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
        events.publishEvent(new BillingEvents.PaymentDue(invoice.getId(), invoice.getInvoiceNumber(),
                invoice.getOrganizationId(), invoice.getTenantId(), invoice.balance(), invoice.getDueDate(), true));
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

    private String tenantName(Invoice invoice) {
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

    /**
     * Move-out settlement: refundDue = max(0, depositHeld - outstanding - deductions); otherwise the tenant owes the
     * difference. The refund itself is executed explicitly through the (idempotent) refunds API.
     */
    @Transactional(readOnly = true)
    public SettlementSummary settlement(Tenant tenant, List<Deduction> deductionItems) {
        List<Deduction> items = deductionItems == null ? List.of() : deductionItems;
        BigDecimal deposit = paymentRepository.depositHeldForTenant(tenant.getId());
        BigDecimal outstanding = invoiceRepository.outstandingForTenant(tenant.getId());
        BigDecimal deductions = items.stream().map(Deduction::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal net = deposit.subtract(outstanding).subtract(deductions);
        return new SettlementSummary(deposit, outstanding, deductions, net.max(BigDecimal.ZERO),
                net.negate().max(BigDecimal.ZERO), items);
    }

    private Invoice lockAccessible(Long id) {
        Invoice invoice = invoiceRepository.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Invoice", id));
        accessGuard.requirePropertyAccess(invoice.getPropertyId());
        return invoice;
    }

    private void requireAccess(Invoice invoice) {
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
