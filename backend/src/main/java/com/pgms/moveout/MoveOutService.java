package com.pgms.moveout;

import com.pgms.audit.AuditService;
import com.pgms.billing.Invoice;
import com.pgms.billing.InvoiceLineItem;
import com.pgms.billing.InvoiceRepository;
import com.pgms.billing.InvoiceService;
import com.pgms.billing.InvoiceService.SettlementCharge;
import com.pgms.billing.InvoiceStatus;
import com.pgms.billing.InvoiceType;
import com.pgms.billing.LineItemType;
import com.pgms.billing.RentCalculator;
import com.pgms.booking.Allocation;
import com.pgms.booking.AllocationRepository;
import com.pgms.booking.AllocationService;
import com.pgms.booking.AllocationStatus;
import com.pgms.common.api.PageResponse;
import com.pgms.common.api.SortValidator;
import com.pgms.common.exception.BadRequestException;
import com.pgms.common.exception.BusinessRuleException;
import com.pgms.common.exception.ConflictException;
import com.pgms.common.exception.ForbiddenException;
import com.pgms.common.exception.NotFoundException;
import com.pgms.deposit.DepositAccountRepository;
import com.pgms.deposit.DepositDtos.RefundDepositRequest;
import com.pgms.deposit.DepositOperations;
import com.pgms.deposit.DepositService;
import com.pgms.moveout.MoveOutCalculator.NoticeTerms;
import com.pgms.moveout.MoveOutDtos.ChangeDateRequest;
import com.pgms.moveout.MoveOutDtos.ChargeLine;
import com.pgms.moveout.MoveOutDtos.CloseRequest;
import com.pgms.moveout.MoveOutDtos.CompleteRequest;
import com.pgms.moveout.MoveOutDtos.CreateMoveOutRequest;
import com.pgms.moveout.MoveOutDtos.ExtraDeduction;
import com.pgms.moveout.MoveOutDtos.InspectionRequest;
import com.pgms.moveout.MoveOutDtos.MoveOutResponse;
import com.pgms.moveout.MoveOutDtos.OpenInvoice;
import com.pgms.moveout.MoveOutDtos.PreviewResponse;
import com.pgms.moveout.MoveOutDtos.RefundRequest;
import com.pgms.moveout.MoveOutDtos.SettlementPreview;
import com.pgms.moveout.MoveOutEnums.Initiator;
import com.pgms.moveout.MoveOutEnums.ItemCondition;
import com.pgms.moveout.MoveOutEnums.Reason;
import com.pgms.moveout.MoveOutEnums.Status;
import com.pgms.moveout.MoveOutPolicy.FinalMonthBilling;
import com.pgms.security.AccessGuard;
import com.pgms.security.AccessScope;
import com.pgms.security.CurrentUserProvider;
import com.pgms.tenant.Tenant;
import com.pgms.tenant.TenantRepository;
import com.pgms.tenant.TenantStatus;
import com.pgms.user.RoleName;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * The move-out workflow: notice (resident or staff) with policy-driven shortfall / lock-in charges, withdrawal, date
 * changes, inspection checklist, settlement wizard (preview + complete) and closing. Every write locks the resident
 * first, then the case, then the allocation (global order tenant -> move-out -> allocation -> bed -> deposit ->
 * invoices -> payments).
 */
@Service
public class MoveOutService {

    private static final Set<String> SORTABLE = Set.of("expectedMoveOutDate", "createdAt", "noticeDate");
    private static final Set<InvoiceStatus> OPEN = EnumSet.of(InvoiceStatus.ISSUED, InvoiceStatus.PARTIALLY_PAID,
            InvoiceStatus.OVERDUE);

    private final MoveOutRepository repository;
    private final MoveOutSupport support;
    private final MoveOutPolicyService policyService;
    private final AllocationRepository allocationRepository;
    private final AllocationService allocationService;
    private final TenantRepository tenantRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceService invoiceService;
    private final DepositOperations depositOperations;
    private final DepositService depositService;
    private final DepositAccountRepository depositAccountRepository;
    private final AccessGuard accessGuard;
    private final CurrentUserProvider currentUserProvider;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public MoveOutService(MoveOutRepository repository, MoveOutSupport support, MoveOutPolicyService policyService,
                          AllocationRepository allocationRepository, AllocationService allocationService,
                          TenantRepository tenantRepository, InvoiceRepository invoiceRepository, InvoiceService invoiceService,
                          DepositOperations depositOperations, DepositService depositService,
                          DepositAccountRepository depositAccountRepository, AccessGuard accessGuard,
                          CurrentUserProvider currentUserProvider, AuditService auditService, ApplicationEventPublisher events,
                          Clock clock) {
        this.repository = repository;
        this.support = support;
        this.policyService = policyService;
        this.allocationRepository = allocationRepository;
        this.allocationService = allocationService;
        this.tenantRepository = tenantRepository;
        this.invoiceRepository = invoiceRepository;
        this.invoiceService = invoiceService;
        this.depositOperations = depositOperations;
        this.depositService = depositService;
        this.depositAccountRepository = depositAccountRepository;
        this.accessGuard = accessGuard;
        this.currentUserProvider = currentUserProvider;
        this.auditService = auditService;
        this.events = events;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ notice

    @Transactional(readOnly = true)
    public PreviewResponse preview(Long allocationId, LocalDate moveOutDate) {
        Allocation allocation = readableActiveAllocation(allocationId);
        LocalDate today = LocalDate.now(clock);
        LocalDate noticeDate = repository.findAll(openCaseOf(allocation.getTenantId())).stream().findFirst()
                .map(MoveOut::getNoticeDate).orElse(today);
        LocalDate date = moveOutDate == null ? today : moveOutDate;
        NoticeTerms terms = support.terms(allocation, noticeDate, date);
        BigDecimal held = depositOperations.heldFor(allocation);
        BigDecimal dues = invoiceRepository.findByTenantIdAndStatusInOrderByDueDate(allocation.getTenantId(), OPEN).stream()
                .filter(i -> i.getInvoiceType() != InvoiceType.SECURITY_DEPOSIT)
                .map(Invoice::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal net = held.subtract(dues).subtract(terms.totalCharges());
        MoveOutPolicy policy = policyService.effective(allocation.getPropertyId());
        return new PreviewResponse(allocation.getId(), allocation.getTenantId(), noticeDate, date, terms.noticePeriodDays(),
                terms.earliestNoPenaltyDate(), terms.shortfallDays(), terms.shortfallCharge(), terms.lockInEndDate(),
                terms.lockInPenalty(), allocation.getMonthlyRent(), policy.getFinalMonthBilling(), held, dues,
                net.max(BigDecimal.ZERO), net.negate().max(BigDecimal.ZERO));
    }

    @Transactional
    public MoveOutResponse create(CreateMoveOutRequest request) {
        LocalDate today = LocalDate.now(clock);
        boolean byTenant = accessGuard.isTenant();
        Tenant tenant;
        Allocation allocation;
        if (byTenant) {
            Long ownId = accessGuard.requireCurrentTenant().getId();
            tenant = tenantRepository.findByIdForUpdate(ownId).orElseThrow(() -> new NotFoundException("Tenant", ownId));
            Long activeId = allocationRepository.findByTenantIdAndStatus(ownId, AllocationStatus.ACTIVE)
                    .orElseThrow(() -> new BusinessRuleException("NOT_A_RESIDENT", "You do not have an active stay"))
                    .getId();
            allocation = allocationRepository.findByIdForUpdate(activeId).orElseThrow();
        } else {
            if (request.allocationId() == null) {
                throw new BadRequestException("ALLOCATION_REQUIRED", "allocationId is required");
            }
            Long tenantId = allocationRepository.findTenantIdById(request.allocationId())
                    .orElseThrow(() -> new NotFoundException("Allocation", request.allocationId()));
            tenant = tenantRepository.findByIdForUpdate(tenantId).orElseThrow(() -> new NotFoundException("Tenant", tenantId));
            allocation = allocationRepository.findByIdForUpdate(request.allocationId()).orElseThrow();
            accessGuard.requirePropertyAccess(allocation.getPropertyId());
        }
        if (!allocation.isActive()) {
            throw new ConflictException("ALLOCATION_NOT_ACTIVE", "Allocation " + allocation.getId() + " is " + allocation.getStatus());
        }
        if (repository.findOpenForTenantForUpdate(tenant.getId()).isPresent()) {
            throw new ConflictException("MOVE_OUT_ALREADY_OPEN", "A move-out is already in progress for this resident");
        }
        if (request.expectedMoveOutDate().isBefore(today)) {
            throw new BusinessRuleException("INVALID_DATE", "Move-out date cannot be in the past");
        }
        if (!request.expectedMoveOutDate().isAfter(allocation.getStartDate())) {
            throw new BusinessRuleException("ZERO_LENGTH_STAY", "Move-out date must be after the check-in date");
        }
        boolean eviction = request.reason() == Reason.EVICTION;
        if (eviction && !isAdmin()) {
            throw new ForbiddenException("Only administrators can record an eviction");
        }
        MoveOut moveOut = new MoveOut(support.nextReference(), allocation.getOrganizationId(), allocation.getPropertyId(),
                tenant.getId(), allocation.getId(), currentUserProvider.currentUserId().orElse(null),
                byTenant ? Initiator.TENANT : Initiator.STAFF, today, request.expectedMoveOutDate(), request.reason(),
                blankToNull(request.reasonText()), blankToNull(request.refundUpiVpa()));
        NoticeTerms terms = support.terms(allocation, today, request.expectedMoveOutDate());
        moveOut.applyTerms(terms);
        if (eviction) {
            moveOut.waiveCharges();
        }
        repository.saveAndFlush(moveOut);
        allocation.recordNotice(today, request.expectedMoveOutDate());
        if (tenant.getStatus() == TenantStatus.ACTIVE) {
            tenant.giveNotice();
        }
        auditService.record(moveOut.getOrganizationId(), "MOVE_OUT_REQUESTED", "MOVE_OUT", moveOut.getId(), Map.of(
                "allocationId", allocation.getId(), "expectedMoveOutDate", request.expectedMoveOutDate().toString(),
                "initiatedBy", moveOut.getInitiatedBy().name(), "shortfallCharge", terms.shortfallCharge(),
                "lockInPenalty", terms.lockInPenalty()));
        events.publishEvent(new MoveOutEvents.MoveOutRequested(moveOut.getId(), moveOut.getReference(),
                moveOut.getOrganizationId(), moveOut.getPropertyId(), tenant.getId(), tenant.getFullName(),
                moveOut.getExpectedMoveOutDate(), moveOut.getShortfallDays(), moveOut.effectiveShortfall(),
                moveOut.getInitiatedBy()));
        return support.toResponse(moveOut, true);
    }

    @Transactional
    public MoveOutResponse withdraw(Long id) {
        MoveOut moveOut = lockCase(id, true);
        moveOut.withdraw(clock.instant());
        Tenant tenant = tenantRepository.findById(moveOut.getTenantId()).orElseThrow();
        Allocation allocation = allocationRepository.findByIdForUpdate(moveOut.getAllocationId()).orElseThrow();
        if (allocation.isActive()) {
            allocation.clearNotice();
        }
        if (tenant.getStatus() == TenantStatus.ON_NOTICE) {
            tenant.withdrawNotice();
        }
        auditService.record(moveOut.getOrganizationId(), "MOVE_OUT_WITHDRAWN", "MOVE_OUT", id);
        events.publishEvent(new MoveOutEvents.MoveOutWithdrawn(id, moveOut.getReference(), moveOut.getOrganizationId(),
                moveOut.getPropertyId(), moveOut.getTenantId(), tenant.getFullName()));
        return support.toResponse(moveOut, true);
    }

    @Transactional
    public MoveOutResponse changeDate(Long id, ChangeDateRequest request) {
        MoveOut moveOut = lockCase(id, false);
        if (request.expectedMoveOutDate().isBefore(LocalDate.now(clock))) {
            throw new BusinessRuleException("INVALID_DATE", "Move-out date cannot be in the past");
        }
        Allocation allocation = allocationRepository.findByIdForUpdate(moveOut.getAllocationId()).orElseThrow();
        NoticeTerms terms = support.terms(allocation, moveOut.getNoticeDate(), request.expectedMoveOutDate());
        LocalDate previous = moveOut.getExpectedMoveOutDate();
        moveOut.reschedule(request.expectedMoveOutDate(), terms, request.waiveCharges() || moveOut.isChargesWaived());
        if (allocation.isActive()) {
            allocation.recordNotice(moveOut.getNoticeDate(), request.expectedMoveOutDate());
        }
        auditService.record(moveOut.getOrganizationId(), "MOVE_OUT_DATE_CHANGED", "MOVE_OUT", id, Map.of(
                "from", previous.toString(), "to", request.expectedMoveOutDate().toString(),
                "chargesWaived", moveOut.isChargesWaived(), "reason", request.reason()));
        return support.toResponse(moveOut, true);
    }

    // ------------------------------------------------------------------ inspection

    @Transactional
    public MoveOutResponse inspect(Long id, InspectionRequest request) {
        MoveOut moveOut = lockCase(id, false);
        List<MoveOut.Item> items = request.items().stream().map(i -> new MoveOut.Item(i.item().trim(), i.condition(),
                i.deductionAmount() == null ? BigDecimal.ZERO : i.deductionAmount(), blankToNull(i.note()))).toList();
        moveOut.recordInspection(items, blankToNull(request.notes()), currentUserProvider.currentUserId().orElse(null),
                clock.instant());
        BigDecimal total = items.stream().map(MoveOut.Item::deductionAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        auditService.record(moveOut.getOrganizationId(), "MOVE_OUT_INSPECTED", "MOVE_OUT", id,
                Map.of("items", items.size(), "deductions", total));
        return support.toResponse(moveOut, true);
    }

    // ------------------------------------------------------------------ settlement

    /** Wizard preview: dues, deductions and how the deposit would cover them, without changing anything. */
    @Transactional(readOnly = true)
    public SettlementPreview settlementPreview(Long id, LocalDate actualMoveOutDate, Boolean waiveCharges) {
        MoveOut moveOut = readableCase(id);
        requireStaff();
        Allocation allocation = allocationRepository.findById(moveOut.getAllocationId()).orElseThrow();
        LocalDate date = actualMoveOutDate == null ? LocalDate.now(clock) : actualMoveOutDate;
        MoveOutPolicy policy = policyService.effective(allocation.getPropertyId());
        boolean prorate = policy.getFinalMonthBilling() == FinalMonthBilling.PRORATED;
        List<OpenInvoice> open = new ArrayList<>();
        BigDecimal credit = BigDecimal.ZERO;
        for (Invoice i : invoiceRepository.findByTenantIdAndStatusInOrderByDueDate(moveOut.getTenantId(), OPEN)) {
            boolean voidedAtCheckOut = i.getInvoiceType() == InvoiceType.SECURITY_DEPOSIT
                    || i.getPeriodStart() != null && !i.getPeriodStart().isBefore(date);
            if (voidedAtCheckOut) {
                continue;
            }
            BigDecimal balance = i.balance();
            if (prorate && i.getInvoiceType() == InvoiceType.RENT && i.getPeriodEnd() != null && !i.getPeriodEnd().isBefore(date)) {
                BigDecimal rentLines = i.getLineItems().stream().filter(l -> l.getLineType() == LineItemType.RENT)
                        .map(InvoiceLineItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal unused = RentCalculator.unusedRentCredit(rentLines, i.getPeriodStart(), i.getPeriodEnd(), date);
                BigDecimal discount = unused.min(balance);
                balance = balance.subtract(discount);
                credit = credit.add(unused.subtract(discount));
            }
            if (balance.signum() > 0) {
                open.add(new OpenInvoice(i.getId(), i.getInvoiceNumber(), i.getInvoiceType(), i.getStatus(), i.getDueDate(),
                        balance));
            }
        }
        boolean waived = waiveCharges != null ? waiveCharges : moveOut.isChargesWaived();
        List<ChargeLine> charges = charges(moveOut, List.of(), waived).stream()
                .map(c -> new ChargeLine(c.type(), c.description(), c.amount())).toList();
        BigDecimal dues = open.stream().map(OpenInvoice::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal deductions = charges.stream().map(ChargeLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal held = depositOperations.heldFor(allocation);
        BigDecimal funds = held.add(credit);
        BigDecimal duesCovered = funds.min(dues);
        BigDecimal deductionsCovered = funds.subtract(duesCovered).min(deductions);
        BigDecimal net = funds.subtract(dues).subtract(deductions);
        return new SettlementPreview(id, date, open, dues, charges, deductions, held, credit, duesCovered, deductionsCovered,
                net.max(BigDecimal.ZERO), net.negate().max(BigDecimal.ZERO), policy.getFinalMonthBilling(),
                policy.getDepositRefundDays());
    }

    @Transactional
    public MoveOutResponse complete(Long id, CompleteRequest request) {
        MoveOut moveOut = lockCase(id, false);
        if (moveOut.getStatus() == Status.NOTICE_GIVEN && !(request.skipInspection() && isAdmin())) {
            throw new ConflictException("MOVE_OUT_NOT_INSPECTED",
                    "Record the inspection first (administrators may complete with skipInspection=true)");
        }
        if (!moveOut.getStatus().isOpen()) {
            throw new ConflictException("MOVE_OUT_INVALID_STATE", "Move-out " + moveOut.getReference() + " is "
                    + moveOut.getStatus() + " and cannot be settled");
        }
        if (Boolean.TRUE.equals(request.waiveCharges())) {
            moveOut.waiveCharges();
        }
        List<SettlementCharge> charges = charges(moveOut, request.extraDeductions() == null ? List.of()
                : request.extraDeductions(), moveOut.isChargesWaived());
        allocationService.performCheckOut(moveOut.getAllocationId(), request.actualMoveOutDate(),
                request.sendBedToMaintenance(), charges, moveOut.getId());
        return support.toResponse(moveOut, true);
    }

    @Transactional
    public MoveOutResponse close(Long id, CloseRequest request) {
        MoveOut moveOut = lockCase(id, false);
        if (moveOut.getStatus() != Status.SETTLED) {
            throw new ConflictException("MOVE_OUT_INVALID_STATE", "Only a SETTLED move-out can be closed");
        }
        if (moveOut.getDepositAccountId() != null) {
            depositAccountRepository.findById(moveOut.getDepositAccountId())
                    .filter(account -> account.getStatus().refundPending()).ifPresent(account -> {
                        throw new ConflictException("REFUND_PENDING",
                                "Refund " + account.getRefundDue() + " is still due; pay it out or forfeit it first");
                    });
        }
        BigDecimal outstanding = invoiceRepository.outstandingForTenant(moveOut.getTenantId());
        if (outstanding.signum() > 0 && !request.writeOffDues()) {
            throw new ConflictException("DUES_OUTSTANDING",
                    "The resident still owes " + outstanding + "; collect it or close with writeOffDues=true");
        }
        BigDecimal writtenOff = BigDecimal.ZERO;
        if (outstanding.signum() > 0) {
            for (Invoice invoice : invoiceService.lockOpenInvoices(moveOut.getTenantId())) {
                BigDecimal balance = invoice.balance();
                if (balance.signum() > 0) {
                    invoiceService.applyDiscount(invoice.getId(), "Written off at move-out close: " + request.note().trim(),
                            balance);
                    writtenOff = writtenOff.add(balance);
                }
            }
            auditService.record(moveOut.getOrganizationId(), "DUES_WRITTEN_OFF", "MOVE_OUT", id, Map.of("amount", writtenOff));
        }
        moveOut.close(request.note().trim(), clock.instant());
        auditService.record(moveOut.getOrganizationId(), "MOVE_OUT_CLOSED", "MOVE_OUT", id, Map.of("writtenOff", writtenOff));
        return support.toResponse(moveOut, true);
    }

    /** Pays out the refund due of a settled case (delegates to the idempotent deposit refund). */
    public MoveOutResponse refund(Long id, String key, RefundRequest request) {
        Long accountId = settledAccountOf(id);
        depositService.refund(accountId, key, new RefundDepositRequest(request.amount(),
                request.method(), request.reference(), request.note()));
        return get(id);
    }

    private Long settledAccountOf(Long id) {
        MoveOut moveOut = readableCase(id);
        if (moveOut.getDepositAccountId() == null || moveOut.getSettledAt() == null) {
            throw new ConflictException("MOVE_OUT_NOT_SETTLED", "The move-out has not been settled yet");
        }
        return moveOut.getDepositAccountId();
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public MoveOutResponse get(Long id) {
        return support.toResponse(readableCase(id), true);
    }

    @Transactional(readOnly = true)
    public PageResponse<MoveOutResponse> search(Long propertyId, Status status, Long tenantId, LocalDate from, LocalDate to,
                                                Pageable pageable) {
        Pageable page = SortValidator.validate(pageable, SORTABLE, Sort.by(Sort.Direction.ASC, "expectedMoveOutDate"));
        Specification<MoveOut> spec;
        if (accessGuard.isTenant()) {
            Long ownId = accessGuard.requireCurrentTenant().getId();
            spec = (root, q, cb) -> cb.equal(root.get("tenantId"), ownId);
        } else {
            AccessScope scope = accessGuard.scopeFor(propertyId);
            if (scope.isEmpty()) {
                return PageResponse.from(Page.<MoveOut>empty(page), m -> support.toResponse(m, false));
            }
            spec = scope.toSpecification();
            if (tenantId != null) {
                spec = spec.and((root, q, cb) -> cb.equal(root.get("tenantId"), tenantId));
            }
        }
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        if (from != null) {
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("expectedMoveOutDate"), from));
        }
        if (to != null) {
            spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("expectedMoveOutDate"), to));
        }
        return PageResponse.from(repository.findAll(spec, page), m -> support.toResponse(m, false));
    }

    // ------------------------------------------------------------------ helpers

    /** Shortfall, lock-in (unless waived), inspection deductions and extra deductions as settlement invoice lines. */
    private List<SettlementCharge> charges(MoveOut moveOut, List<ExtraDeduction> extra, boolean waived) {
        List<SettlementCharge> charges = new ArrayList<>();
        if (!waived && moveOut.getShortfallCharge().signum() > 0) {
            charges.add(new SettlementCharge(LineItemType.NOTICE_SHORTFALL,
                    "Notice shortfall: " + moveOut.getShortfallDays() + " days", moveOut.getShortfallCharge()));
        }
        if (!waived && moveOut.getLockInPenalty().signum() > 0) {
            charges.add(new SettlementCharge(LineItemType.DEDUCTION, "Lock-in penalty (left before the lock-in ended)",
                    moveOut.getLockInPenalty()));
        }
        moveOut.getItems().stream().filter(i -> i.getDeductionAmount().signum() > 0)
                .sorted(Comparator.comparing(MoveOutInspectionItem::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(i -> charges.add(new SettlementCharge(
                        i.getCondition() == ItemCondition.DAMAGED || i.getCondition() == ItemCondition.MISSING
                                ? LineItemType.DAMAGE : LineItemType.DEDUCTION,
                        truncate(label(i.getCondition()) + ": " + i.getItem() + (i.getNote() == null ? "" : " - " + i.getNote())),
                        i.getDeductionAmount())));
        for (ExtraDeduction e : extra) {
            LineItemType type = e.type() == null ? LineItemType.DEDUCTION : e.type();
            if (type != LineItemType.DEDUCTION && type != LineItemType.DAMAGE && type != LineItemType.OTHER) {
                throw new BadRequestException("INVALID_DEDUCTION_TYPE", "Extra deductions must be DEDUCTION, DAMAGE or OTHER");
            }
            charges.add(new SettlementCharge(type, e.description().trim(), e.amount()));
        }
        return charges;
    }

    private static String label(ItemCondition condition) {
        return switch (condition) {
            case OK -> "Deduction";
            case CLEANING -> "Cleaning";
            case DAMAGED -> "Damaged";
            case MISSING -> "Missing";
        };
    }

    /**
     * Locks resident then case and checks access: residents only their own case and only when {@code tenantAllowed};
     * staff need access to the case's property.
     */
    private MoveOut lockCase(Long id, boolean tenantAllowed) {
        Long tenantId = repository.findTenantIdById(id).orElseThrow(() -> new NotFoundException("Move-out", id));
        if (accessGuard.isTenant()) {
            if (!tenantAllowed || !accessGuard.requireCurrentTenant().getId().equals(tenantId)) {
                throw new NotFoundException("Move-out", id);
            }
        }
        tenantRepository.findByIdForUpdate(tenantId).orElseThrow(() -> new NotFoundException("Tenant", tenantId));
        MoveOut moveOut = repository.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Move-out", id));
        if (!accessGuard.isTenant()) {
            accessGuard.requirePropertyAccess(moveOut.getPropertyId());
        }
        return moveOut;
    }

    private MoveOut readableCase(Long id) {
        MoveOut moveOut = repository.findWithItems(id).orElseThrow(() -> new NotFoundException("Move-out", id));
        if (accessGuard.isTenant()) {
            if (!accessGuard.requireCurrentTenant().getId().equals(moveOut.getTenantId())) {
                throw new NotFoundException("Move-out", id);
            }
        } else {
            accessGuard.requirePropertyAccess(moveOut.getPropertyId());
        }
        return moveOut;
    }

    private Allocation readableActiveAllocation(Long allocationId) {
        Allocation allocation;
        if (accessGuard.isTenant()) {
            Long ownId = accessGuard.requireCurrentTenant().getId();
            allocation = allocationRepository.findByTenantIdAndStatus(ownId, AllocationStatus.ACTIVE)
                    .orElseThrow(() -> new BusinessRuleException("NOT_A_RESIDENT", "You do not have an active stay"));
            if (allocationId != null && !allocationId.equals(allocation.getId())) {
                throw new NotFoundException("Allocation", allocationId);
            }
            return allocation;
        }
        if (allocationId == null) {
            throw new BadRequestException("ALLOCATION_REQUIRED", "allocationId is required");
        }
        allocation = allocationRepository.findById(allocationId).orElseThrow(() -> new NotFoundException("Allocation", allocationId));
        accessGuard.requirePropertyAccess(allocation.getPropertyId());
        if (!allocation.isActive()) {
            throw new ConflictException("ALLOCATION_NOT_ACTIVE", "Allocation " + allocationId + " is " + allocation.getStatus());
        }
        return allocation;
    }

    private static Specification<MoveOut> openCaseOf(Long tenantId) {
        return (root, q, cb) -> cb.and(cb.equal(root.get("tenantId"), tenantId),
                root.get("status").in(Status.NOTICE_GIVEN, Status.INSPECTED));
    }

    private void requireStaff() {
        if (accessGuard.isTenant()) {
            throw new ForbiddenException("Not available for tenant accounts");
        }
    }

    private boolean isAdmin() {
        RoleName role = accessGuard.currentUser().role();
        return role == RoleName.SUPER_ADMIN || role == RoleName.PROPERTY_ADMIN;
    }

    private static String truncate(String text) {
        return text.length() <= 255 ? text : text.substring(0, 255);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
