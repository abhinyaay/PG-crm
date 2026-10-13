package com.pgms.billing;

import com.pgms.audit.AuditService;
import com.pgms.billing.CollectionsDtos.CreateRecurringChargeRequest;
import com.pgms.billing.CollectionsDtos.RecurringChargeResponse;
import com.pgms.billing.CollectionsDtos.UpdateRecurringChargeRequest;
import com.pgms.booking.AllocationRepository;
import com.pgms.common.exception.BadRequestException;
import com.pgms.common.exception.ConflictException;
import com.pgms.common.exception.NotFoundException;
import com.pgms.tenant.Tenant;
import com.pgms.tenant.TenantService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Recurring monthly add-ons of a resident and their snapshot into rent invoices. */
@Service
public class RecurringChargeService {

    static final Set<LineItemType> ALLOWED_TYPES = EnumSet.of(LineItemType.FOOD, LineItemType.LAUNDRY, LineItemType.PARKING,
            LineItemType.INTERNET, LineItemType.OTHER);

    private final RecurringChargeRepository repository;
    private final AllocationRepository allocationRepository;
    private final TenantService tenantService;
    private final AuditService auditService;
    private final Clock clock;

    public RecurringChargeService(RecurringChargeRepository repository, AllocationRepository allocationRepository,
                                  TenantService tenantService, AuditService auditService, Clock clock) {
        this.repository = repository;
        this.allocationRepository = allocationRepository;
        this.tenantService = tenantService;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * Adds one line per active charge of the invoice's resident covering {@code month} (charges pinned to another
     * allocation are skipped). On a prorated move-in invoice a charge with {@code prorateFirstMonth} is prorated like
     * the rent. Runs inside the invoice-creating transaction, before the invoice is saved.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int addRecurringLines(Invoice invoice, YearMonth month, Long userId) {
        if (invoice.getInvoiceType() != InvoiceType.RENT) {
            return 0;
        }
        boolean prorated = invoice.getPeriodStart() != null && invoice.getPeriodStart().getDayOfMonth() != 1;
        LocalDate today = LocalDate.now(clock);
        int added = 0;
        for (RecurringCharge charge : repository.findByTenantIdAndActiveTrueOrderByIdAsc(invoice.getTenantId())) {
            if (!charge.covers(month)
                    || charge.getAllocationId() != null && !charge.getAllocationId().equals(invoice.getAllocationId())) {
                continue;
            }
            BigDecimal amount = charge.getAmount();
            String description = charge.getDescription();
            if (prorated && charge.isProrateFirstMonth()) {
                amount = RentCalculator.proratedRent(amount, invoice.getPeriodStart());
                description = description + " (prorated from " + invoice.getPeriodStart() + ")";
            }
            if (amount.signum() > 0) {
                invoice.addCharge(charge.getLineType(), description, amount, clock.instant(), userId, today);
                added++;
            }
        }
        return added;
    }

    @Transactional(readOnly = true)
    public List<RecurringChargeResponse> list(Long tenantId) {
        tenantService.loadAccessible(tenantId);
        return repository.findByTenantIdOrderByIdAsc(tenantId).stream().map(RecurringChargeResponse::of).toList();
    }

    @Transactional
    public RecurringChargeResponse create(Long tenantId, CreateRecurringChargeRequest request) {
        Tenant tenant = tenantService.loadAccessible(tenantId);
        if (!ALLOWED_TYPES.contains(request.type())) {
            throw new BadRequestException("INVALID_CHARGE_TYPE", "Recurring charges can be " + ALLOWED_TYPES);
        }
        YearMonth start = month(request.startMonth(), "startMonth");
        YearMonth end = request.endMonth() == null ? null : month(request.endMonth(), "endMonth");
        requireOrdered(start, end);
        if (request.allocationId() != null) {
            allocationRepository.findById(request.allocationId())
                    .filter(a -> a.getTenantId().equals(tenantId))
                    .orElseThrow(() -> new NotFoundException("Allocation", request.allocationId()));
        }
        RecurringCharge charge = repository.saveAndFlush(new RecurringCharge(tenant.getOrganizationId(), tenant.getPropertyId(),
                tenantId, request.allocationId(), request.type(), request.description().trim(), request.amount(), start, end,
                request.prorateFirstMonth()));
        auditService.record(tenant.getOrganizationId(), "RECURRING_CHARGE_CREATED", "RECURRING_CHARGE", charge.getId(),
                details(charge));
        return RecurringChargeResponse.of(charge);
    }

    @Transactional
    public RecurringChargeResponse update(Long id, UpdateRecurringChargeRequest request) {
        RecurringCharge charge = loadAccessible(id);
        if (!Objects.equals(charge.getVersion(), request.version())) {
            throw new ConflictException("CONCURRENT_MODIFICATION", "The charge was changed by someone else. Reload and retry.");
        }
        YearMonth end = request.endMonth() == null ? null : month(request.endMonth(), "endMonth");
        requireOrdered(YearMonth.from(charge.getStartMonth()), end);
        charge.update(request.description().trim(), request.amount(), end, request.prorateFirstMonth(), request.active());
        repository.flush();
        auditService.record(charge.getOrganizationId(), "RECURRING_CHARGE_UPDATED", "RECURRING_CHARGE", id, details(charge));
        return RecurringChargeResponse.of(charge);
    }

    /** Ends the charge (kept for history; invoices already issued keep their lines). */
    @Transactional
    public void delete(Long id) {
        RecurringCharge charge = loadAccessible(id);
        charge.deactivate();
        auditService.record(charge.getOrganizationId(), "RECURRING_CHARGE_ENDED", "RECURRING_CHARGE", id, details(charge));
    }

    private RecurringCharge loadAccessible(Long id) {
        RecurringCharge charge = repository.findById(id).orElseThrow(() -> new NotFoundException("RecurringCharge", id));
        tenantService.loadAccessible(charge.getTenantId());
        return charge;
    }

    private static YearMonth month(String value, String field) {
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("INVALID_MONTH", field + " must be yyyy-MM");
        }
    }

    private static void requireOrdered(YearMonth start, YearMonth end) {
        if (end != null && end.isBefore(start)) {
            throw new BadRequestException("INVALID_MONTH", "endMonth cannot be before startMonth");
        }
    }

    private static Map<String, Object> details(RecurringCharge charge) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("tenantId", charge.getTenantId());
        details.put("type", charge.getLineType().name());
        details.put("amount", charge.getAmount());
        details.put("startMonth", charge.getStartMonth().toString());
        details.put("endMonth", Objects.toString(charge.getEndMonth(), null));
        details.put("active", charge.isActive());
        return details;
    }
}
