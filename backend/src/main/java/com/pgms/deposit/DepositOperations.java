package com.pgms.deposit;

import com.pgms.audit.AuditService;
import com.pgms.billing.BillingEvents;
import com.pgms.billing.InternalMoneyMovements;
import com.pgms.billing.Invoice;
import com.pgms.billing.InvoiceRepository;
import com.pgms.billing.InvoiceService;
import com.pgms.billing.InvoiceService.RentCredit;
import com.pgms.billing.InvoiceService.SettlementCharge;
import com.pgms.billing.InvoiceType;
import com.pgms.billing.LineItemType;
import com.pgms.billing.Payment;
import com.pgms.billing.PaymentMethod;
import com.pgms.billing.PaymentRepository;
import com.pgms.billing.Refund;
import com.pgms.billing.RefundKind;
import com.pgms.billing.RefundRepository;
import com.pgms.booking.Allocation;
import com.pgms.booking.AllocationRepository;
import com.pgms.booking.BookingEvents;
import com.pgms.common.exception.BadRequestException;
import com.pgms.common.exception.BusinessRuleException;
import com.pgms.common.exception.ConflictException;
import com.pgms.common.exception.NotFoundException;
import com.pgms.deposit.DepositDtos.ActionResult;
import com.pgms.deposit.DepositDtos.DeductionRequest;
import com.pgms.deposit.DepositDtos.DepositActionResponse;
import com.pgms.deposit.DepositDtos.DepositSummary;
import com.pgms.deposit.DepositDtos.ForfeitRequest;
import com.pgms.deposit.DepositDtos.LedgerLine;
import com.pgms.deposit.DepositDtos.RefundDepositRequest;
import com.pgms.deposit.DepositDtos.TopUpRequest;
import com.pgms.deposit.DepositMath.Fund;
import com.pgms.deposit.DepositMath.Slice;
import com.pgms.security.AccessGuard;
import com.pgms.security.CurrentUserProvider;
import com.pgms.tenant.Tenant;
import com.pgms.tenant.TenantRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * All writes to deposits. Every operation locks the resident row first and the deposit account second (global lock
 * order: tenant -> move-out -> allocation -> bed -> deposit account -> invoices -> payments), so settlement, refunds
 * and deductions of one resident never interleave. Money moves through {@link InternalMoneyMovements}, keyed by the
 * ledger entry id, and user actions are idempotent through {@code (organization, Idempotency-Key)}.
 */
@Service
public class DepositOperations {

    static final Set<PaymentMethod> PAYOUT_METHODS =
            EnumSet.of(PaymentMethod.CASH, PaymentMethod.UPI, PaymentMethod.BANK_TRANSFER, PaymentMethod.CHEQUE);

    private final DepositAccountRepository accountRepository;
    private final DepositAccountAllocationRepository mappingRepository;
    private final DepositTransactionRepository transactionRepository;
    private final AllocationRepository allocationRepository;
    private final TenantRepository tenantRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final InvoiceService invoiceService;
    private final InternalMoneyMovements movements;
    private final DepositQueries queries;
    private final AccessGuard accessGuard;
    private final CurrentUserProvider currentUserProvider;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public DepositOperations(DepositAccountRepository accountRepository, DepositAccountAllocationRepository mappingRepository,
                             DepositTransactionRepository transactionRepository, AllocationRepository allocationRepository,
                             TenantRepository tenantRepository, InvoiceRepository invoiceRepository,
                             PaymentRepository paymentRepository, RefundRepository refundRepository,
                             InvoiceService invoiceService, InternalMoneyMovements movements, DepositQueries queries,
                             AccessGuard accessGuard, CurrentUserProvider currentUserProvider, AuditService auditService,
                             ApplicationEventPublisher events, Clock clock) {
        this.accountRepository = accountRepository;
        this.mappingRepository = mappingRepository;
        this.transactionRepository = transactionRepository;
        this.allocationRepository = allocationRepository;
        this.tenantRepository = tenantRepository;
        this.invoiceRepository = invoiceRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.invoiceService = invoiceService;
        this.movements = movements;
        this.queries = queries;
        this.accessGuard = accessGuard;
        this.currentUserProvider = currentUserProvider;
        this.auditService = auditService;
        this.events = events;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ stay chain bookkeeping

    /**
     * The deposit account of the stay chain {@code allocation} belongs to, created (and every allocation of the chain
     * mapped to it) on first use. Check-in and transfer create it eagerly through events; older stays are created by
     * {@link DepositAccountBackfill} or lazily here.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public DepositAccount ensureAccount(Allocation allocation) {
        Optional<DepositAccountAllocation> mapping = mappingRepository.findById(allocation.getId());
        if (mapping.isPresent()) {
            return accountRepository.findById(mapping.get().getDepositAccountId())
                    .orElseThrow(() -> new NotFoundException("Deposit", mapping.get().getDepositAccountId()));
        }
        Allocation root = allocation;
        while (root.getPreviousAllocationId() != null) {
            Long previousId = root.getPreviousAllocationId();
            root = allocationRepository.findById(previousId).orElseThrow(() -> new NotFoundException("Allocation", previousId));
        }
        Allocation first = root;
        DepositAccount account = accountRepository.findByRootAllocationId(root.getId()).orElseGet(() ->
                accountRepository.saveAndFlush(new DepositAccount(first.getOrganizationId(), allocation.getPropertyId(),
                        allocation.getTenantId(), first.getId(), allocation.getId())));
        Allocation tail = root;
        for (Allocation current = root; current != null;
             current = allocationRepository.findFirstByPreviousAllocationIdOrderByIdDesc(current.getId()).orElse(null)) {
            if (!mappingRepository.existsById(current.getId())) {
                mappingRepository.save(new DepositAccountAllocation(current.getId(), account.getId()));
            }
            tail = current;
        }
        if (!tail.getId().equals(account.getCurrentAllocationId())) {
            account.followTransfer(tail.getId(), tail.getPropertyId());
        }
        mappingRepository.flush();
        return account;
    }

    @EventListener
    public void on(BookingEvents.TenantCheckedIn e) {
        allocationRepository.findById(e.allocationId()).ifPresent(this::ensureAccount);
    }

    @EventListener
    public void on(BookingEvents.TenantTransferred e) {
        allocationRepository.findById(e.toAllocationId()).ifPresent(next ->
                ensureAccount(next).followTransfer(next.getId(), next.getPropertyId()));
    }

    /**
     * A refund recorded manually through {@code POST /payments/{id}/refunds} on a deposit payment is mirrored into the
     * ledger, and lowers the refund due when the deposit was already settled.
     */
    @EventListener
    public void on(BillingEvents.RefundIssued e) {
        Refund refund = refundRepository.findById(e.refundId()).orElse(null);
        if (refund == null || refund.getKind() != RefundKind.CASH || refund.getDepositTransactionId() != null
                || transactionRepository.existsByRefundId(refund.getId())) {
            return;
        }
        Payment payment = paymentRepository.findById(refund.getPaymentId()).orElseThrow();
        Invoice invoice = invoiceRepository.findById(payment.getInvoiceId()).orElseThrow();
        if (invoice.getInvoiceType() != InvoiceType.SECURITY_DEPOSIT) {
            return;
        }
        DepositAccount account = ensureAccount(allocationRepository.findById(invoice.getAllocationId()).orElseThrow());
        transactionRepository.save(new DepositTransaction(account, DepositTransactionType.REFUND, refund.getAmount(),
                "Refund " + refund.getRefundReference() + ": " + refund.getReason(), refund.getRefundedAt(),
                currentUserProvider.currentUserId().orElse(null)).withRefund(refund.getId()));
        account.reduceRefundDue(refund.getAmount(), true);
    }

    // ------------------------------------------------------------------ funds

    /** Deposit money still held (chain deposit payments net of refunds/adjustments), oldest payment first. */
    List<Fund> depositFunds(DepositAccount account) {
        List<Long> allocationIds = mappingRepository.findAllocationIds(account.getId());
        if (allocationIds.isEmpty()) {
            return List.of();
        }
        return paymentRepository.findDepositPaymentsForAllocations(allocationIds).stream()
                .filter(p -> p.refundableAmount().signum() > 0)
                .map(p -> new Fund(p.getId(), p.refundableAmount(), null)).toList();
    }

    /** Deposit funds followed by unused rent credit (each capped by what its rent payment can still give back). */
    List<Fund> allFunds(DepositAccount account) {
        List<Fund> funds = new ArrayList<>(depositFunds(account));
        for (DepositTransaction credit : transactionRepository.findOpenCredits(account.getId())) {
            BigDecimal refundable = paymentRepository.findById(credit.getPaymentId()).map(Payment::refundableAmount)
                    .orElse(BigDecimal.ZERO);
            BigDecimal available = credit.getRemaining().min(refundable);
            if (available.signum() > 0) {
                funds.add(new Fund(credit.getPaymentId(), available, credit.getId()));
            }
        }
        return funds;
    }

    /** Deposit held for the stay {@code allocation} belongs to (0 when nothing was collected). Read-only. */
    @Transactional(readOnly = true)
    public BigDecimal heldFor(Allocation allocation) {
        return mappingRepository.findById(allocation.getId())
                .flatMap(m -> accountRepository.findById(m.getDepositAccountId()))
                .map(account -> account.isSettled() ? BigDecimal.ZERO : DepositMath.total(depositFunds(account)))
                .orElseGet(() -> allocation.getPreviousAllocationId() == null
                        ? paymentRepository.findDepositPaymentsForAllocations(List.of(allocation.getId())).stream()
                                .map(Payment::refundableAmount).reduce(BigDecimal.ZERO, BigDecimal::add)
                        : BigDecimal.ZERO);
    }

    // ------------------------------------------------------------------ settlement

    /** What the settlement did with the deposit. */
    public record SettlementOutcome(BigDecimal depositHeld, BigDecimal rentCredit, BigDecimal duesAdjusted,
                                    BigDecimal deductionsApplied, BigDecimal refundDue, BigDecimal payableByTenant,
                                    LocalDate refundDueBy) {
    }

    /**
     * Applies the stay's deposit (then rent credit) to the resident's open invoices, oldest due first, and records the
     * remainder as refund due. {@code openInvoices} must already be locked by the caller (ascending id).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SettlementOutcome settle(DepositAccount account, Long moveOutId, LocalDate moveOutDate, List<RentCredit> credits,
                                    List<Invoice> openInvoices, int refundDays) {
        DepositAccount locked = accountRepository.findByIdForUpdate(account.getId()).orElseThrow();
        if (locked.isSettled()) {
            throw new ConflictException("DEPOSIT_ALREADY_SETTLED", "The deposit of this stay was already settled");
        }
        Instant now = clock.instant();
        Long userId = currentUserProvider.currentUserId().orElse(null);
        BigDecimal depositHeld = DepositMath.total(depositFunds(locked));
        BigDecimal rentCredit = BigDecimal.ZERO;
        for (RentCredit credit : credits) {
            BigDecimal left = credit.refundable();
            for (Payment payment : paymentRepository.findByInvoiceIdOrderByPaidAt(credit.invoiceId())) {
                if (left.signum() <= 0) {
                    break;
                }
                if (payment.getMethod() == PaymentMethod.DEPOSIT_ADJUSTMENT || payment.refundableAmount().signum() <= 0) {
                    continue;
                }
                BigDecimal take = left.min(payment.refundableAmount());
                transactionRepository.save(new DepositTransaction(locked, DepositTransactionType.RENT_CREDIT, take,
                        "Rent paid for days not stayed (" + credit.invoiceNumber() + ")", now, userId)
                        .withInvoice(credit.invoiceId()).withPayment(payment.getId()).withMoveOut(moveOutId));
                left = left.subtract(take);
                rentCredit = rentCredit.add(take);
            }
        }
        transactionRepository.flush();

        List<Invoice> dues = openInvoices.stream().filter(i -> i.isOpen() && i.balance().signum() > 0)
                .sorted(Comparator.comparing(Invoice::getDueDate).thenComparing(Invoice::getId)).toList();
        Map<Long, Invoice> byId = dues.stream().collect(Collectors.toMap(Invoice::getId, Function.identity()));
        DepositMath.Waterfall waterfall = DepositMath.waterfall(allFunds(locked),
                dues.stream().map(i -> new DepositMath.Due(i.getId(), i.balance())).toList());
        BigDecimal duesAdjusted = BigDecimal.ZERO;
        BigDecimal deductions = BigDecimal.ZERO;
        for (Slice slice : waterfall.slices()) {
            Invoice target = byId.get(slice.invoiceId());
            boolean deduction = target.getInvoiceType() == InvoiceType.SETTLEMENT;
            DepositTransaction tx = transactionRepository.saveAndFlush(new DepositTransaction(locked,
                    deduction ? DepositTransactionType.DEDUCTION : DepositTransactionType.DUES_ADJUSTED, slice.amount(),
                    (deduction ? "Deductions " : "Dues ") + target.getInvoiceNumber() + " paid from "
                            + (slice.fund().isCredit() ? "rent credit" : "deposit"), now, userId)
                    .withInvoice(target.getId()).withMoveOut(moveOutId));
            moveMoney(slice, target.getId(), "dep-" + tx.getId(), tx);
            if (deduction) {
                deductions = deductions.add(slice.amount());
            } else {
                duesAdjusted = duesAdjusted.add(slice.amount());
            }
        }
        LocalDate dueBy = LocalDate.now(clock).plusDays(refundDays);
        locked.settle(moveOutDate, waterfall.fundsLeft(), dueBy, now);
        auditService.record(locked.getOrganizationId(), "DEPOSIT_SETTLED", "DEPOSIT", locked.getId(), Map.of(
                "depositHeld", depositHeld, "rentCredit", rentCredit, "duesAdjusted", duesAdjusted,
                "deductions", deductions, "refundDue", waterfall.fundsLeft(), "payableByTenant", waterfall.duesLeft()));
        return new SettlementOutcome(depositHeld, rentCredit, duesAdjusted, deductions, waterfall.fundsLeft(),
                waterfall.duesLeft(), locked.getRefundDueBy());
    }

    // ------------------------------------------------------------------ owner actions

    @Transactional
    public ActionResult refund(Long accountId, String key, String fingerprint, RefundDepositRequest request) {
        DepositAccount account = lockForAction(accountId);
        Optional<ActionResult> replay = replay(account, key, fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (!PAYOUT_METHODS.contains(request.method())) {
            throw new BadRequestException("INVALID_REFUND_METHOD", "Refund method must be one of " + PAYOUT_METHODS);
        }
        if (!account.getStatus().refundPending()) {
            throw new ConflictException("NO_REFUND_DUE", "No refund is due on this deposit (" + account.getStatus() + ")");
        }
        BigDecimal amount = request.amount() == null ? account.getRefundDue() : request.amount();
        if (amount.compareTo(account.getRefundDue()) > 0) {
            throw new BusinessRuleException("REFUND_EXCEEDS_DUE",
                    "Refund " + amount + " exceeds the refund due " + account.getRefundDue());
        }
        List<Fund> funds = allFunds(account);
        if (DepositMath.total(funds).compareTo(amount) < 0) {
            throw new BusinessRuleException("INSUFFICIENT_DEPOSIT_FUNDS",
                    "Only " + DepositMath.total(funds) + " of the deposit can still be paid out");
        }
        String note = blankToNull(request.note()) == null ? "Deposit refund" : request.note().trim();
        DepositTransaction tx = transactionRepository.saveAndFlush(new DepositTransaction(account,
                DepositTransactionType.REFUND, amount, note, clock.instant(), currentUserProvider.currentUserId().orElse(null))
                .withPayout(request.method(), blankToNull(request.reference())).withIdempotency(key, fingerprint));
        int n = 0;
        for (Slice slice : DepositMath.draw(funds, amount)) {
            movements.payout(slice.fund().paymentId(), slice.amount(), "dep-" + tx.getId() + "-" + ++n, note,
                    request.method(), blankToNull(request.reference()), tx.getId());
            consumeCredit(slice);
        }
        account.recordRefund(amount);
        auditService.record(account.getOrganizationId(), "DEPOSIT_REFUNDED", "DEPOSIT", account.getId(), Map.of(
                "amount", amount, "method", request.method().name(), "refundDueAfter", account.getRefundDue()));
        events.publishEvent(new DepositEvents.DepositRefunded(account.getId(), tx.getId(), account.getOrganizationId(),
                account.getPropertyId(), account.getTenantId(), amount, request.method(), blankToNull(request.reference()),
                account.getRefundDue()));
        return new ActionResult(response(account, tx, null), false);
    }

    @Transactional
    public ActionResult deduct(Long accountId, String key, String fingerprint, DeductionRequest request) {
        DepositAccount account = lockForAction(accountId);
        Optional<ActionResult> replay = replay(account, key, fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }
        LineItemType type = request.type() == null ? LineItemType.DEDUCTION : request.type();
        if (type != LineItemType.DEDUCTION && type != LineItemType.DAMAGE) {
            throw new BadRequestException("INVALID_DEDUCTION_TYPE", "Deduction type must be DEDUCTION or DAMAGE");
        }
        if (account.getStatus() != DepositStatus.HELD && !account.getStatus().refundPending()) {
            throw new ConflictException("DEPOSIT_CLOSED", "Nothing is left on this deposit (" + account.getStatus() + ")");
        }
        List<Fund> funds = account.isSettled() ? allFunds(account) : depositFunds(account);
        BigDecimal available = account.isSettled() ? account.getRefundDue().min(DepositMath.total(funds))
                : DepositMath.total(funds);
        if (request.amount().compareTo(available) > 0) {
            throw new BusinessRuleException("DEDUCTION_EXCEEDS_DEPOSIT",
                    "Deduction " + request.amount() + " exceeds what is left of the deposit (" + available + ")");
        }
        String description = request.description().trim();
        Posted posted = postAndApply(account, DepositTransactionType.DEDUCTION, type, description, description,
                request.amount(), funds, key, fingerprint);
        if (account.isSettled()) {
            account.reduceRefundDue(request.amount(), false);
        }
        auditService.record(account.getOrganizationId(), "DEPOSIT_DEDUCTION_RECORDED", "DEPOSIT", account.getId(),
                Map.of("amount", request.amount(), "description", description, "invoiceId", posted.invoice().getId()));
        return new ActionResult(response(account, posted.transaction(), posted.invoice().getId()), false);
    }

    @Transactional
    public ActionResult forfeit(Long accountId, String key, String fingerprint, ForfeitRequest request) {
        DepositAccount account = lockForAction(accountId);
        Optional<ActionResult> replay = replay(account, key, fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }
        Allocation current = allocationRepository.findById(account.getCurrentAllocationId()).orElseThrow();
        if (account.getStatus() == DepositStatus.HELD && current.isActive()) {
            throw new ConflictException("STAY_ACTIVE", "A deposit can be forfeited only after the resident has moved out");
        }
        List<Fund> funds = account.isSettled() ? allFunds(account) : depositFunds(account);
        BigDecimal amount = account.isSettled() ? account.getRefundDue().min(DepositMath.total(funds)) : DepositMath.total(funds);
        String reason = request.reason().trim();
        Posted posted = amount.signum() > 0
                ? postAndApply(account, DepositTransactionType.FORFEIT, LineItemType.DEDUCTION, "Deposit forfeited: " + reason,
                        "Forfeited: " + reason, amount, funds, key, fingerprint)
                : null;
        account.forfeit(reason, LocalDate.now(clock), clock.instant());
        auditService.record(account.getOrganizationId(), "DEPOSIT_FORFEITED", "DEPOSIT", account.getId(),
                Map.of("amount", amount, "reason", reason));
        return new ActionResult(response(account, posted == null ? null : posted.transaction(),
                posted == null ? null : posted.invoice().getId()), false);
    }

    @Transactional
    public ActionResult topUp(Long accountId, String key, String fingerprint, TopUpRequest request) {
        DepositAccount account = lockForAction(accountId);
        Optional<ActionResult> replay = replay(account, key, fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }
        Allocation current = allocationRepository.findById(account.getCurrentAllocationId()).orElseThrow();
        if (account.isSettled() || !current.isActive()) {
            throw new ConflictException("STAY_NOT_ACTIVE", "A top-up can be requested only for a running stay");
        }
        BigDecimal amount = request.amount();
        if (amount == null) {
            DepositSummary summary = summary(account);
            amount = summary.agreedDeposit().subtract(summary.held()).subtract(summary.pendingCollection());
        }
        if (amount.signum() <= 0) {
            throw new BusinessRuleException("TOP_UP_NOT_REQUIRED",
                    "The deposit held and invoiced already covers the agreed deposit of the current bed");
        }
        String reason = request.reason().trim();
        Invoice invoice = invoiceService.issueDepositTopUp(current, amount, "Deposit top-up: " + reason);
        DepositTransaction tx = transactionRepository.saveAndFlush(new DepositTransaction(account,
                DepositTransactionType.TOP_UP_REQUESTED, amount, "Top-up requested: " + reason, clock.instant(),
                currentUserProvider.currentUserId().orElse(null)).withInvoice(invoice.getId()).withIdempotency(key, fingerprint));
        auditService.record(account.getOrganizationId(), "DEPOSIT_TOP_UP_REQUESTED", "DEPOSIT", account.getId(),
                Map.of("amount", amount, "invoiceId", invoice.getId()));
        return new ActionResult(response(account, tx, invoice.getId()), false);
    }

    /** Reports one overdue refund (job item, own transaction); false when it is not overdue or already reported today. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reportOverdueRefund(Long accountId, LocalDate today) {
        DepositAccount account = accountRepository.findByIdForUpdate(accountId).orElse(null);
        if (account == null || !account.isRefundOverdue(today) || today.equals(account.getOverdueNotifiedOn())) {
            return false;
        }
        account.markOverdueNotified(today);
        String tenantName = tenantRepository.findById(account.getTenantId()).map(Tenant::getFullName).orElse("Resident");
        events.publishEvent(new DepositEvents.DepositRefundOverdue(account.getId(), account.getOrganizationId(),
                account.getPropertyId(), account.getTenantId(), tenantName, account.getRefundDue(), account.getRefundDueBy(),
                today));
        return true;
    }

    // ------------------------------------------------------------------ helpers

    private record Posted(Invoice invoice, DepositTransaction transaction) {
    }

    /** Charges the current allocation's settlement invoice and pays it from the given funds in one ledger entry. */
    private Posted postAndApply(DepositAccount account, DepositTransactionType txType, LineItemType lineType,
                                 String lineDescription, String txDescription, BigDecimal amount, List<Fund> funds,
                                 String key, String fingerprint) {
        Allocation allocation = allocationRepository.findById(account.getCurrentAllocationId()).orElseThrow();
        Invoice invoice = invoiceService.addSettlementCharges(allocation,
                List.of(new SettlementCharge(lineType, lineDescription, amount)));
        DepositTransaction tx = transactionRepository.saveAndFlush(new DepositTransaction(account, txType, amount,
                txDescription, clock.instant(), currentUserProvider.currentUserId().orElse(null))
                .withInvoice(invoice.getId()).withIdempotency(key, fingerprint));
        int n = 0;
        for (Slice slice : DepositMath.draw(funds, amount)) {
            moveMoney(slice, invoice.getId(), "dep-" + tx.getId() + "-" + ++n, tx);
        }
        return new Posted(invoice, tx);
    }

    private void moveMoney(Slice slice, Long invoiceId, String key, DepositTransaction tx) {
        movements.adjust(slice.fund().paymentId(), invoiceId, slice.amount(), key, tx.getDescription(), tx.getId());
        consumeCredit(slice);
    }

    private void consumeCredit(Slice slice) {
        if (slice.fund().isCredit()) {
            transactionRepository.findById(slice.fund().creditTransactionId()).orElseThrow().consume(slice.amount());
        }
    }

    /** Locks resident then account (lock order) and checks the caller may act on the account's property. */
    private DepositAccount lockForAction(Long accountId) {
        Long tenantId = accountRepository.findTenantIdById(accountId).orElseThrow(() -> new NotFoundException("Deposit", accountId));
        tenantRepository.findByIdForUpdate(tenantId).orElseThrow(() -> new NotFoundException("Tenant", tenantId));
        DepositAccount account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new NotFoundException("Deposit", accountId));
        accessGuard.requirePropertyAccess(account.getPropertyId());
        return account;
    }

    /** Under the account lock an earlier committed request with the same key is visible: return it unchanged. */
    Optional<ActionResult> replay(DepositAccount account, String key, String fingerprint) {
        if (key == null) {
            return Optional.empty();
        }
        return transactionRepository.findByOrganizationIdAndIdempotencyKey(account.getOrganizationId(), key).map(tx -> {
            if (!tx.getDepositAccountId().equals(account.getId()) || !fingerprint.equals(tx.getRequestFingerprint())) {
                throw new BusinessRuleException("IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was already used for a different request");
            }
            return new ActionResult(response(account, tx, tx.getInvoiceId()), true);
        });
    }

    DepositSummary summary(DepositAccount account) {
        accountRepository.flush();
        return queries.page(new DepositQueries.Filter(null, null, null, account.getId(), null, null, LocalDate.now(clock)),
                null, 1, 0).get(0);
    }

    private DepositActionResponse response(DepositAccount account, DepositTransaction tx, Long invoiceId) {
        return new DepositActionResponse(summary(account), tx == null ? null : line(tx), invoiceId);
    }

    static LedgerLine line(DepositTransaction tx) {
        BigDecimal signed = switch (tx.getType()) {
            case RENT_CREDIT -> tx.getAmount();
            case TOP_UP_REQUESTED -> BigDecimal.ZERO;
            default -> tx.getAmount().negate();
        };
        return new LedgerLine(tx.getCreatedAt(), tx.getType().name(), signed, null, tx.getDescription(), tx.getInvoiceId(),
                tx.getPaymentId(), tx.getMethod(), tx.getReference(), tx.getId());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
