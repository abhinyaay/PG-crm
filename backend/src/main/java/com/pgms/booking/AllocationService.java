package com.pgms.booking;

import com.pgms.audit.AuditService;
import com.pgms.billing.BillingDtos.InvoiceSummary;
import com.pgms.billing.BillingDtos.SettlementSummary;
import com.pgms.billing.InvoiceService;
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
    private final Clock clock;

    public AllocationService(AllocationRepository allocationRepository, BookingRepository bookingRepository,
                             BedRepository bedRepository, TenantRepository tenantRepository, TenantService tenantService,
                             BookingService bookingService, InvoiceService invoiceService, AccessGuard accessGuard,
                             AuditService auditService, ApplicationEventPublisher events, MeterRegistry meterRegistry,
                             Clock clock) {
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

        BigDecimal rent = request.monthlyRent() != null ? request.monthlyRent() : bed.getRoom().getMonthlyRent();
        BigDecimal deposit = request.securityDeposit() != null ? request.securityDeposit() : bed.getRoom().getSecurityDeposit();
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
        return new CheckInResponse(AllocationResponse.of(allocation), invoices);
    }

    @Transactional
    public CheckOutResponse checkOut(Long allocationId, CheckOutRequest request) {
        LocalDate today = LocalDate.now(clock);
        requireDateWindow(request.checkOutDate(), today, "Check-out");
        Tenant tenant = lockTenantOfAllocation(allocationId);
        Allocation allocation = lockActiveAllocation(allocationId);

        allocation.end(request.checkOutDate(), EndReason.CHECK_OUT);
        Bed bed = bedRepository.findByIdForUpdate(allocation.getBedId())
                .orElseThrow(() -> new NotFoundException("Bed", allocation.getBedId()));
        bed.vacate();
        if (request.sendBedToMaintenance()) {
            bed.startMaintenance();
        }
        tenant.moveOut(request.checkOutDate());
        int invoicesClosed = invoiceService.closeOutAfterMoveOut(tenant.getId(), request.checkOutDate());
        SettlementSummary settlement = invoiceService.settlement(tenant, request.deductions());

        auditService.record(allocation.getOrganizationId(), "TENANT_CHECKED_OUT", "ALLOCATION", allocationId, Map.of(
                "checkOutDate", request.checkOutDate().toString(),
                "invoicesClosed", invoicesClosed,
                "refundDue", settlement.refundDue(),
                "amountPayableByTenant", settlement.amountPayableByTenant()));
        events.publishEvent(new BookingEvents.TenantCheckedOut(allocationId, allocation.getOrganizationId(),
                allocation.getPropertyId(), tenant.getId(), bed.getId(), request.checkOutDate()));
        meterRegistry.counter("pgms.allocations.checked_out").increment();
        return new CheckOutResponse(AllocationResponse.of(allocation), settlement);
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

        BigDecimal rent = request.newMonthlyRent() != null ? request.newMonthlyRent() : targetBed.getRoom().getMonthlyRent();
        Allocation next = new Allocation(current.getOrganizationId(), targetProperty.getId(), tenant.getId(), targetBed.getId(),
                null, current.getId(), request.transferDate(), rent, current.getSecurityDeposit());
        next.inheritNotice(current);
        allocationRepository.saveAndFlush(next);
        if (!Objects.equals(tenant.getPropertyId(), targetProperty.getId())) {
            tenant.relocateTo(targetProperty.getId());
        }

        auditService.record(current.getOrganizationId(), "TENANT_TRANSFERRED", "ALLOCATION", next.getId(), Map.of(
                "fromAllocationId", current.getId(), "fromBedId", currentBed.getId(), "toBedId", targetBed.getId(),
                "monthlyRent", rent));
        events.publishEvent(new BookingEvents.TenantTransferred(current.getId(), next.getId(), current.getOrganizationId(),
                tenant.getId(), currentBed.getId(), targetBed.getId(), request.transferDate()));
        meterRegistry.counter("pgms.allocations.transferred").increment();
        return new TransferResponse(AllocationResponse.of(current), AllocationResponse.of(next));
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
        return AllocationResponse.of(allocation);
    }

    @Transactional
    public AllocationResponse withdrawNotice(Long allocationId) {
        Tenant tenant = lockTenantOfAllocation(allocationId);
        Allocation allocation = lockActiveAllocation(allocationId);
        allocation.clearNotice();
        tenant.withdrawNotice();
        auditService.record(allocation.getOrganizationId(), "NOTICE_WITHDRAWN", "ALLOCATION", allocationId);
        return AllocationResponse.of(allocation);
    }

    @Transactional(readOnly = true)
    public AllocationResponse get(Long id) {
        Allocation allocation = allocationRepository.findById(id).orElseThrow(() -> new NotFoundException("Allocation", id));
        requireReadAccess(allocation);
        return AllocationResponse.of(allocation);
    }

    /** Full stay history of a tenant (including transfers), newest first. */
    @Transactional(readOnly = true)
    public List<AllocationResponse> tenantHistory(Long tenantId) {
        tenantService.loadAccessible(tenantId);
        return allocationRepository.findByTenantIdOrderByStartDateDesc(tenantId).stream().map(AllocationResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public List<AllocationResponse> bedHistory(Long bedId) {
        Bed bed = bedRepository.findById(bedId).orElseThrow(() -> new NotFoundException("Bed", bedId));
        accessGuard.requirePropertyAccess(bed.getPropertyId());
        return allocationRepository.findByBedIdOrderByStartDateDesc(bedId).stream().map(AllocationResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<AllocationResponse> search(Long propertyId, Long tenantId, Long bedId, AllocationStatus status,
                                                   LocalDate expectedEndBefore, Pageable pageable) {
        AccessScope scope = accessGuard.scopeFor(propertyId);
        Pageable page = SortValidator.validate(pageable, SORTABLE, Sort.by(Sort.Direction.DESC, "startDate"));
        if (scope.isEmpty()) {
            return PageResponse.from(Page.<Allocation>empty(page), AllocationResponse::of);
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
        return PageResponse.from(allocationRepository.findAll(spec, page), AllocationResponse::of);
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
