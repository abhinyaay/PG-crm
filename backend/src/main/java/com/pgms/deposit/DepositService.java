package com.pgms.deposit;

import com.pgms.billing.Payment;
import com.pgms.billing.PaymentRepository;
import com.pgms.common.exception.BadRequestException;
import com.pgms.common.exception.ConstraintViolationTranslator;
import com.pgms.common.util.Hashing;
import com.pgms.deposit.DepositDtos.ActionResult;
import com.pgms.deposit.DepositDtos.DeductionRequest;
import com.pgms.deposit.DepositDtos.DepositLedger;
import com.pgms.deposit.DepositDtos.DepositListResponse;
import com.pgms.deposit.DepositDtos.DepositSummary;
import com.pgms.deposit.DepositDtos.DepositTotals;
import com.pgms.deposit.DepositDtos.ForfeitRequest;
import com.pgms.deposit.DepositDtos.LedgerLine;
import com.pgms.deposit.DepositDtos.RefundDepositRequest;
import com.pgms.deposit.DepositDtos.TenantDepositsResponse;
import com.pgms.deposit.DepositDtos.TopUpRequest;
import com.pgms.security.AccessGuard;
import com.pgms.security.AccessScope;
import com.pgms.tenant.Tenant;
import com.pgms.tenant.TenantService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The owner's Deposits tab: list with totals, per-resident ledger and the idempotent actions (refund, deduction,
 * forfeit, top-up). Writes are delegated to {@link DepositOperations}; this facade resolves the rare idempotency race
 * that can only be answered after the failed transaction rolled back (same key, two different accounts).
 */
@Service
public class DepositService {

    private static final Pattern KEY_FORMAT = Pattern.compile("^[A-Za-z0-9_-]{8,100}$");

    private final DepositOperations operations;
    private final DepositQueries queries;
    private final DepositAccountRepository accountRepository;
    private final DepositAccountAllocationRepository mappingRepository;
    private final DepositTransactionRepository transactionRepository;
    private final PaymentRepository paymentRepository;
    private final TenantService tenantService;
    private final AccessGuard accessGuard;
    private final Clock clock;

    public DepositService(DepositOperations operations, DepositQueries queries, DepositAccountRepository accountRepository,
                          DepositAccountAllocationRepository mappingRepository,
                          DepositTransactionRepository transactionRepository, PaymentRepository paymentRepository,
                          TenantService tenantService, AccessGuard accessGuard, Clock clock) {
        this.operations = operations;
        this.queries = queries;
        this.accountRepository = accountRepository;
        this.mappingRepository = mappingRepository;
        this.transactionRepository = transactionRepository;
        this.paymentRepository = paymentRepository;
        this.tenantService = tenantService;
        this.accessGuard = accessGuard;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DepositListResponse list(Long propertyId, DepositStatus status, Long tenantId, Boolean overdue, Pageable pageable) {
        AccessScope scope = accessGuard.scopeFor(propertyId);
        LocalDate today = LocalDate.now(clock);
        DepositQueries.Filter filter = new DepositQueries.Filter(scope, propertyId, tenantId, null, status, overdue, today);
        int size = pageable.getPageSize();
        if (scope.isEmpty()) {
            return new DepositListResponse(new DepositTotals(BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0),
                    List.of(), pageable.getPageNumber(), size, 0, 0, true);
        }
        long total = queries.count(filter);
        List<DepositSummary> content = queries.page(filter, pageable.getSort(), size, pageable.getOffset());
        int totalPages = size == 0 ? 0 : (int) ((total + size - 1) / size);
        return new DepositListResponse(queries.totals(filter), content, pageable.getPageNumber(), size, total, totalPages,
                pageable.getPageNumber() + 1 >= totalPages);
    }

    /** Every stay's deposit of one resident with its ledger, newest stay first (a resident sees their own). */
    @Transactional(readOnly = true)
    public TenantDepositsResponse ledger(Long tenantId) {
        Tenant tenant = tenantService.loadAccessible(tenantId);
        LocalDate today = LocalDate.now(clock);
        List<DepositLedger> stays = new ArrayList<>();
        for (DepositAccount account : accountRepository.findByTenantIdOrderByIdDesc(tenant.getId())) {
            DepositSummary summary = queries.page(new DepositQueries.Filter(null, null, null, account.getId(), null, null,
                    today), null, 1, 0).get(0);
            stays.add(new DepositLedger(summary, lines(account)));
        }
        return new TenantDepositsResponse(tenant.getId(), tenant.getFullName(), stays);
    }

    @Transactional(readOnly = true)
    public TenantDepositsResponse myLedger() {
        return ledger(accessGuard.requireCurrentTenant().getId());
    }

    public ActionResult refund(Long accountId, String key, RefundDepositRequest request) {
        requireKey(key);
        String fingerprint = fingerprint("refund", accountId, amount(request.amount()), request.method(), request.reference(),
                request.note());
        return idempotent(key, fingerprint, () -> operations.refund(accountId, key, fingerprint, request));
    }

    public ActionResult deduct(Long accountId, String key, DeductionRequest request) {
        validateOptionalKey(key);
        String fingerprint = fingerprint("deduction", accountId, amount(request.amount()), request.type(),
                request.description());
        return idempotent(key, fingerprint, () -> operations.deduct(accountId, key, fingerprint, request));
    }

    public ActionResult forfeit(Long accountId, String key, ForfeitRequest request) {
        validateOptionalKey(key);
        String fingerprint = fingerprint("forfeit", accountId, request.reason());
        return idempotent(key, fingerprint, () -> operations.forfeit(accountId, key, fingerprint, request));
    }

    public ActionResult topUp(Long accountId, String key, TopUpRequest request) {
        validateOptionalKey(key);
        String fingerprint = fingerprint("top-up", accountId, amount(request.amount()), request.reason());
        return idempotent(key, fingerprint, () -> operations.topUp(accountId, key, fingerprint, request));
    }

    private ActionResult idempotent(String key, String fingerprint, Supplier<ActionResult> action) {
        try {
            return action.get();
        } catch (DataIntegrityViolationException e) {
            if (key == null || !ConstraintViolationTranslator.isConstraint(e, "uk_deposit_tx_idempotency")) {
                throw e;
            }
            return replayAfterRace(key, fingerprint).orElseThrow(() -> e);
        }
    }

    private Optional<ActionResult> replayAfterRace(String key, String fingerprint) {
        Long organizationId = accessGuard.currentUser().organizationId();
        return transactionRepository.findByOrganizationIdAndIdempotencyKey(organizationId, key)
                .flatMap(tx -> accountRepository.findById(tx.getDepositAccountId()))
                .flatMap(account -> operations.replay(account, key, fingerprint));
    }

    private List<LedgerLine> lines(DepositAccount account) {
        List<LedgerLine> lines = new ArrayList<>();
        List<Long> allocationIds = mappingRepository.findAllocationIds(account.getId());
        if (!allocationIds.isEmpty()) {
            for (Payment p : paymentRepository.findDepositPaymentsForAllocations(allocationIds)) {
                lines.add(new LedgerLine(p.getPaidAt(), "COLLECTED", p.getAmount(), null,
                        "Deposit received, receipt " + p.getReceiptNumber(), p.getInvoiceId(), p.getId(), p.getMethod(),
                        p.getExternalReference(), null));
            }
        }
        transactionRepository.findByDepositAccountIdOrderByCreatedAtAscIdAsc(account.getId())
                .forEach(tx -> lines.add(DepositOperations.line(tx)));
        lines.sort(Comparator.comparing(LedgerLine::at).thenComparing(l -> l.transactionId() == null ? 0L : l.transactionId()));
        List<LedgerLine> withBalance = new ArrayList<>(lines.size());
        BigDecimal balance = BigDecimal.ZERO;
        for (LedgerLine line : lines) {
            balance = balance.add(line.amount());
            withBalance.add(new LedgerLine(line.at(), line.type(), line.amount(), balance, line.description(), line.invoiceId(),
                    line.paymentId(), line.method(), line.reference(), line.transactionId()));
        }
        return withBalance;
    }

    private static String amount(BigDecimal value) {
        return value == null ? "default" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String fingerprint(Object... parts) {
        StringBuilder canonical = new StringBuilder();
        for (Object part : parts) {
            canonical.append(Objects.toString(part, "")).append('|');
        }
        return Hashing.sha256Hex(canonical.toString());
    }

    private static void requireKey(String key) {
        if (key == null || !KEY_FORMAT.matcher(key).matches()) {
            throw new BadRequestException("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key header must be 8-100 characters of letters, digits, '-' or '_' (a UUID is recommended)");
        }
    }

    private static void validateOptionalKey(String key) {
        if (key != null) {
            requireKey(key);
        }
    }
}
