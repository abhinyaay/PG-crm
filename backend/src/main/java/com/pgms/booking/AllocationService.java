package com.pgms.booking;

import com.pgms.audit.AuditService;
import com.pgms.billing.BillingDtos.InvoiceSummary;
import com.pgms.billing.BillingDtos.SettlementSummary;
import com.pgms.billing.InvoiceService;
import com.pgms.billing.InvoiceService.SettlementCharge;
import com.pgms.billing.LineItemType;
import com.pgms.booking.BookingDtos.AllocationResponse;
import com.pgms.booking.BookingDtos.CheckInRequest;
import com.pgms.booking.BookingDtos.CheckInResponse;
import com.pgms.booking.BookingDtos.CheckOutRequest;
import com.pgms.booking.BookingDtos.CheckOutResponse;
import com.pgms.booking.BookingDtos.TransferRequest;
import com.pgms.booking.BookingDtos.TransferResponse;
import com.pgms.common.api.PageResponse;
import com.pgms.common.api.SortValidator;
import com.pgms.common.exception.BadRequestException;
import com.pgms.common.exception.BusinessRuleException;
import com.pgms.common.exception.ConflictException;
import com.pgms.common.exception.NotFoundException;
import com.pgms.common.lookup.DisplayNames;
import com.pgms.property.Property;
import com.pgms.room.Bed;
import com.pgms.room.BedRepository;
import com.pgms.security.AccessGuard;
import com.pgms.security.AccessScope;
import com.pgms.tenant.Tenant;
import com.pgms.tenant.TenantRepository;
import com.pgms.tenant.TenantService;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Check-in, check-out, transfer and notice. Every write method is a single ACID transaction: bed status, allocation,
 * tenant status, booking and invoices change together or not at all.
 *
 * <p>Double allocation is prevented in three layers: (1) {@code SELECT ... FOR UPDATE} on the bed (and tenant)
 * serialises concurrent requests, (2) the bed state machine rejects a non-AVAILABLE/RESERVED bed after the lock,
 * (3) PostgreSQL exclusion constraints reject any overlapping [start, end) allocation for the same bed or tenant
 * even if application logic were bypassed.
 */
@Service
public class AllocationService {

    private static final Set<String> SORTABLE = Set.of("startDate", "endDate", "createdAt");
    private static final int MAX_BACKDATE_DAYS = 31;

    private final AllocationRepository allocationRepository;
    private final BookingRepository bookingRepository;
    private final BedRepository bedRepository;
    private final TenantRepository tenantRepository;
    private final TenantService tenantService;
    private final BookingService bookingService;
    private final InvoiceService invoiceService;
    private final AccessGuard accessGuard;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final MeterRegistry meterRegistry;
    private final CheckOutSettlement checkOutSettlement;
    private final DisplayNames displayNames;
    private final Clock clock;

    public AllocationService(AllocationRepository allocationRepository, BookingRepository bookingRepository,
                             BedRepository bedRepository, TenantRepository tenantRepository, TenantService tenantService,
                             BookingService bookingService, InvoiceService invoiceService, AccessGuard accessGuard,
                             AuditService auditService, ApplicationEventPublisher events, MeterRegistry meterRegistry,
                             CheckOutSettlement checkOutSettlement, DisplayNames displayNames, Clock clock) {
        this.allocationRepository = allocationRepository;
        this.bookingRepository = bookingRepository;
        this.bedRepository = bedRepository;
        this.tenantRepository = tenantRepository;
        this.tenantService = tenantService;
        this.bookingService = bookingService;
        this.invoiceService = invoiceService;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
        this.events = events;
        this.meterRegistry = meterRegistry;
        this.checkOutSettlement = checkOutSettlement;
        this.displayNames = displayNames;
        this.clock = clock;
    }

    @Transactional
    public CheckInResponse checkIn(CheckInRequest request) {
        if (request.bookingId() == null && request.bedId() == null) {
            throw new BadRequestException("BED_OR_BOOKING_REQUIRED", "Provide bookingId (reserved check-in) or bedId (walk-in)");
        }
        LocalDate today = LocalDate.now(clock);
        requireDateWindow(request.checkInDate(), today, "Check-in");

        // lock order: tenant -> booking -> bed
        Tenant tenant = tenantRepository.findByIdForUpdate(request.tenantId())
                .orElseThrow(() -> new NotFoundException("Tenant", request.tenantId()));
        accessGuard.requirePropertyAccess(tenant.getPropertyId());
        if (tenant.isResident()) {
            throw new BusinessRuleException("TENANT_ALREADY_CHECKED_IN", "Tenant already occupies a bed");
        }

        Booking booking = null;
        Long bedId = request.bedId();
        if (request.bookingId() != null) {
            booking = bookingRepository.findByIdForUpdate(request.bookingId())
                    .orElseThrow(() -> new NotFoundException("Booking", request.bookingId()));
            if (!booking.getTenantId().equals(tenant.getId())) {
                throw new BusinessRuleException("BOOKING_TENANT_MISMATCH", "Booking belongs to a different tenant");
            }
            if (bedId != null && !bedId.equals(booking.getBedId())) {
                throw new BusinessRuleException("BOOKING_BED_MISMATCH", "Booking is for a different bed");
            }
            if (!booking.isLive(clock.instant())) {
                throw new ConflictException("RESERVATION_NOT_ACTIVE",
                        "Reservation " + booking.getBookingReference() + " is " + booking.getStatus() + " or has expired");
            }
            bedId = booking.getBedId();
        } else if (bookingRepository.existsByTenantIdAndStatus(tenant.getId(), BookingStatus.RESERVED)) {
            throw new ConflictException("TENANT_HAS_RESERVATION", "Tenant has an active reservation; check in against it");
        }

        Long lockedBedId = bedId;
        Bed bed = bedRepository.findByIdForUpdate(lockedBedId).orElseThrow(() -> new NotFoundException("Bed", lockedBedId));
        accessGuard.requirePropertyAccess(bed.getPropertyId());
        Property property = bookingService.requireBookable(bed, tenant);
        bed.occupy(booking != null);

        // price precedence: explicit override in the request > price quoted with the reservation > the bed's own price
        // (its per-bed override, else the room's price)
        BigDecimal rent = firstNonNull(request.monthlyRent(), booking == null ? null : booking.getQuotedMonthlyRent(),
                bed.effectiveMonthlyRent());
        BigDecimal deposit = firstNonNull(request.securityDeposit(), booking == null ? null : booking.getQuotedSecurityDeposit(),
                bed.effectiveSecurityDeposit());
        Allocation allocation = allocationRepository.saveAndFlush(new Allocation(property.getOrganizationId(), property.getId(),
                tenant.getId(), bed.getId(), booking == null ? null : booking.getId(), null, request.checkInDate(), rent, deposit));
        if (booking != null) {
            booking.markCheckedIn();
        }
        tenant.checkIn(property.getId(), request.checkInDate());
        List<InvoiceSummary> invoices = invoiceService.issueMoveInInvoices(allocation, property.getRentDueDay()).stream()
                .map(InvoiceSummary::of).toList();

        Map<String, Object> details = new HashMap<>();
        details.put("bedId", bed.getId());
        details.put("tenantId", tenant.getId());
        details.put("monthlyRent", rent);
        details.put("rentOverridden", request.monthlyRent() != null);
        auditService.record(property.getOrganizationId(), "TENANT_CHECKED_IN", "ALLOCATION", allocation.getId(), details);
        events.publishEvent(new BookingEvents.TenantCheckedIn(allocation.getId(), property.getOrganizationId(), property.getId(),
                tenant.getId(), bed.getId(), request.checkInDate()));
        meterRegistry.counter("pgms.allocations.checked_in", "walk_in", String.valueOf(booking == null)).increment();
        return new CheckInResponse(respond(allocation), invoices);
    }

    /**
     * Legacy one-step check-out: ends the stay and settles it with the given deductions. The settlement is persisted
     * (a SETTLED move-out case, deposit applied to dues, refund due recorded) exactly like the move-out workflow, but
     * notice-shortfall and lock-in charges are not applied.
     */
    @Transactional
    public CheckOutResponse checkOut(Long allocationId, CheckOutRequest request) {
        List<SettlementCharge> charges = request.deductions() == null ? List.of() : request.deductions().stream()
                .map(d -> new SettlementCharge(LineItemType.DEDUCTION, d.description(), d.amount())).toList();
        return performCheckOut(allocationId, request.checkOutDate(), request.sendBedToMaintenance(), charges, null);
    }

    /**
     * Ends the allocation, frees the bed, moves the tenant out, closes invoices for periods not stayed and settles the
     * money through {@link CheckOutSettlement}. Lock order: tenant -> allocation -> bed (the move-out module locks
     * the move-out case between tenant and allocation before calling this).
     */
    @Transactional
    public CheckOutResponse performCheckOut(Long allocationId, LocalDate checkOutDate, boolean sendBedToMaintenance,
                                            List<SettlementCharge> charges, Long moveOutId) {
        LocalDate today = LocalDate.now(clock);
        requireDateWindow(checkOutDate, today, "Check-out");
        Tenant tenant = lockTenantOfAllocation(allocationId);
        Allocation allocation = lockActiveAllocation(allocationId);

        allocation.end(checkOutDate, EndReason.CHECK_OUT);
        Bed bed = bedRepository.findByIdForUpdate(allocation.getBedId())
                .orElseThrow(() -> new NotFoundException("Bed", allocation.getBedId()));
        bed.vacate();
        if (sendBedToMaintenance) {
            bed.startMaintenance();
        }
        tenant.moveOut(checkOutDate);
        allocationRepository.flush();
        int invoicesClosed = invoiceService.closeOutAfterMoveOut(tenant.getId(), checkOutDate);
        SettlementSummary settlement = checkOutSettlement.settle(tenant, allocation, checkOutDate, charges, moveOutId);

        auditService.record(allocation.getOrganizationId(), "TENANT_CHECKED_OUT", "ALLOCATION", allocationId, Map.of(
                "checkOutDate", checkOutDate.toString(),
                "invoicesClosed", invoicesClosed,
                "refundDue", settlement.refundDue(),
                "amountPayableByTenant", settlement.amountPayableByTenant()));
        events.publishEvent(new BookingEvents.TenantCheckedOut(allocationId, allocation.getOrganizationId(),
                allocation.getPropertyId(), tenant.getId(), bed.getId(), checkOutDate));
        meterRegistry.counter("pgms.allocations.checked_out").increment();
        return new CheckOutResponse(respond(allocation), settlement);
    }

    /**
     * Moves a resident to another bed (possibly in another property of the same organization). Both beds are locked
     * in ascending id order so two opposite transfers can never deadlock. The old allocation is flushed as ended
     * before the new one is inserted: Hibernate orders INSERTs before UPDATEs at flush time, which would otherwise
     * trip the "one active allocation per tenant" constraint.
     */
    @Transactional
    public TransferResponse transfer(Long allocationId, TransferRequest request) {
        LocalDate today = LocalDate.now(clock);
        requireDateWindow(request.transferDate(), today, "Transfer");
        Tenant tenant = lockTenantOfAllocation(allocationId);
        Allocation current = lockActiveAllocation(allocationId);
        if (current.getBedId().equals(request.targetBedId())) {
            throw new BusinessRuleException("SAME_BED", "Tenant already occupies this bed");
        }

        Long firstId = Math.min(current.getBedId(), request.targetBedId());
        Long secondId = Math.max(current.getBedId(), request.targetBedId());
        Bed first = bedRepository.findByIdForUpdate(firstId).orElseThrow(() -> new NotFoundException("Bed", firstId));
        Bed second = bedRepository.findByIdForUpdate(secondId).orElseThrow(() -> new NotFoundException("Bed", secondId));
        Bed currentBed = first.getId().equals(current.getBedId()) ? first : second;
        Bed targetBed = currentBed == first ? second : first;

        accessGuard.requirePropertyAccess(targetBed.getPropertyId());
        Property targetProperty = bookingService.requireBookable(targetBed, tenant);
        targetBed.occupy(false);
        currentBed.vacate();

        current.end(request.transferDate(), EndReason.TRANSFER);
        allocationRepository.saveAndFlush(current);

        BigDecimal rent = request.newMonthlyRent() != null ? request.newMonthlyRent() : targetBed.effectiveMonthlyRent();
        BigDecimal deposit = request.newSecurityDeposit() != null ? request.newSecurityDeposit() : current.getSecurityDeposit();
        Allocation next = new Allocation(current.getOrganizationId(), targetProperty.getId(), tenant.getId(), targetBed.getId(),
                null, current.getId(), request.transferDate(), rent, deposit);
        next.inheritNotice(current);
        allocationRepository.saveAndFlush(next);
        BigDecimal topUp = deposit.subtract(current.getSecurityDeposit());
        if (topUp.signum() > 0) {
            // deposit difference of a costlier room: billed now as a further deposit invoice on the new allocation
            invoiceService.issueDepositTopUp(next, topUp, "Deposit top-up after room transfer ("
                    + current.getSecurityDeposit().toPlainString() + " to " + deposit.toPlainString() + ")");
        }
        if (!Objects.equals(tenant.getPropertyId(), targetProperty.getId())) {
            tenant.relocateTo(targetProperty.getId());
        }

        auditService.record(current.getOrganizationId(), "TENANT_TRANSFERRED", "ALLOCATION", next.getId(), Map.of(
                "fromAllocationId", current.getId(), "fromBedId", currentBed.getId(), "toBedId", targetBed.getId(),
                "monthlyRent", rent));
        events.publishEvent(new BookingEvents.TenantTransferred(current.getId(), next.getId(), current.getOrganizationId(),
                tenant.getId(), currentBed.getId(), targetBed.getId(), request.transferDate()));
        meterRegistry.counter("pgms.allocations.transferred").increment();
        DisplayNames.Index names = names(List.of(current, next));
        return new TransferResponse(AllocationResponse.of(current, names), AllocationResponse.of(next, names));
    }

    @Transactional
    public AllocationResponse giveNotice(Long allocationId, LocalDate expectedCheckOutDate) {
        LocalDate today = LocalDate.now(clock);
        if (expectedCheckOutDate.isBefore(today)) {
            throw new BusinessRuleException("INVALID_DATE", "Expected check-out date cannot be in the past");
        }
        Tenant tenant = lockTenantOfAllocation(allocationId);
        Allocation allocation = lockActiveAllocation(allocationId);
        allocation.recordNotice(today, expectedCheckOutDate);
        tenant.giveNotice();
        auditService.record(allocation.getOrganizationId(), "NOTICE_GIVEN", "ALLOCATION", allocationId,
                Map.of("expectedCheckOutDate", expectedCheckOutDate.toString()));
        events.publishEvent(new BookingEvents.NoticeGiven(allocationId, allocation.getOrganizationId(),
                allocation.getPropertyId(), tenant.getId(), today, expectedCheckOutDate));
        return respond(allocation);
    }

    @Transactional
    public AllocationResponse withdrawNotice(Long allocationId) {
        Tenant tenant = lockTenantOfAllocation(allocationId);
        Allocation allocation = lockActiveAllocation(allocationId);
        allocation.clearNotice();
        tenant.withdrawNotice();
        auditService.record(allocation.getOrganizationId(), "NOTICE_WITHDRAWN", "ALLOCATION", allocationId);
        events.publishEvent(new BookingEvents.NoticeWithdrawn(allocationId, allocation.getOrganizationId(),
                allocation.getPropertyId(), tenant.getId()));
        return respond(allocation);
    }

    @Transactional(readOnly = true)
    public AllocationResponse get(Long id) {
        Allocation allocation = allocationRepository.findById(id).orElseThrow(() -> new NotFoundException("Allocation", id));
        requireReadAccess(allocation);
        return respond(allocation);
    }

    /** Full stay history of a tenant (including transfers), newest first. */
    @Transactional(readOnly = true)
    public List<AllocationResponse> tenantHistory(Long tenantId) {
        tenantService.loadAccessible(tenantId);
        return respond(allocationRepository.findByTenantIdOrderByStartDateDesc(tenantId));
    }

    @Transactional(readOnly = true)
    public List<AllocationResponse> bedHistory(Long bedId) {
        Bed bed = bedRepository.findById(bedId).orElseThrow(() -> new NotFoundException("Bed", bedId));
        accessGuard.requirePropertyAccess(bed.getPropertyId());
        return respond(allocationRepository.findByBedIdOrderByStartDateDesc(bedId));
    }

    @Transactional(readOnly = true)
    public PageResponse<AllocationResponse> search(Long propertyId, Long tenantId, Long bedId, AllocationStatus status,
                                                   LocalDate expectedEndBefore, Pageable pageable) {
        AccessScope scope = accessGuard.scopeFor(propertyId);
        Pageable page = SortValidator.validate(pageable, SORTABLE, Sort.by(Sort.Direction.DESC, "startDate"));
        if (scope.isEmpty()) {
            return PageResponse.from(Page.<Allocation>empty(page), a -> AllocationResponse.of(a, DisplayNames.Index.EMPTY));
        }
        Specification<Allocation> spec = scope.toSpecification();
        if (tenantId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("tenantId"), tenantId));
        }
        if (bedId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("bedId"), bedId));
        }
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        if (expectedEndBefore != null) {
            spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("expectedEndDate"), expectedEndBefore));
        }
        Page<Allocation> allocations = allocationRepository.findAll(spec, page);
        DisplayNames.Index names = names(allocations.getContent());
        return PageResponse.from(allocations, a -> AllocationResponse.of(a, names));
    }

    private AllocationResponse respond(Allocation allocation) {
        return AllocationResponse.of(allocation, names(List.of(allocation)));
    }

    private List<AllocationResponse> respond(List<Allocation> allocations) {
        DisplayNames.Index names = names(allocations);
        return allocations.stream().map(a -> AllocationResponse.of(a, names)).toList();
    }

    /** Tenant names and bed/room codes for a list of allocations in two queries. */
    private DisplayNames.Index names(List<Allocation> allocations) {
        return displayNames.lookup(DisplayNames.ids(allocations, Allocation::getTenantId), Set.of(),
                DisplayNames.ids(allocations, Allocation::getBedId));
    }

    private static BigDecimal firstNonNull(BigDecimal... candidates) {
        for (BigDecimal candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Reads only the tenant id (a scalar, so no stale allocation entity sits in the persistence context) and locks
     * the tenant first, keeping the global lock order tenant -> allocation -> bed.
     */
    private Tenant lockTenantOfAllocation(Long allocationId) {
        Long tenantId = allocationRepository.findTenantIdById(allocationId)
                .orElseThrow(() -> new NotFoundException("Allocation", allocationId));
        return tenantRepository.findByIdForUpdate(tenantId).orElseThrow(() -> new NotFoundException("Tenant", tenantId));
    }

    private Allocation lockActiveAllocation(Long allocationId) {
        Allocation allocation = allocationRepository.findByIdForUpdate(allocationId)
                .orElseThrow(() -> new NotFoundException("Allocation", allocationId));
        accessGuard.requirePropertyAccess(allocation.getPropertyId());
        if (!allocation.isActive()) {
            throw new ConflictException("ALLOCATION_NOT_ACTIVE", "Allocation " + allocationId + " is " + allocation.getStatus());
        }
        return allocation;
    }

    private void requireReadAccess(Allocation allocation) {
        if (accessGuard.isTenant()) {
            if (!allocation.getTenantId().equals(accessGuard.requireCurrentTenant().getId())) {
                throw new NotFoundException("Allocation", allocation.getId());
            }
            return;
        }
        accessGuard.requirePropertyAccess(allocation.getPropertyId());
    }

    private static void requireDateWindow(LocalDate date, LocalDate today, String action) {
        if (date.isAfter(today)) {
            throw new BusinessRuleException("FUTURE_DATE", action + " date cannot be in the future");
        }
        if (date.isBefore(today.minusDays(MAX_BACKDATE_DAYS))) {
            throw new BusinessRuleException("DATE_TOO_OLD", action + " can be back-dated by at most " + MAX_BACKDATE_DAYS + " days");
        }
    }
}
