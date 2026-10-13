package com.pgms.booking;

import com.pgms.audit.AuditService;
import com.pgms.booking.BookingDtos.BookingResponse;
import com.pgms.booking.BookingDtos.ReserveBedRequest;
import com.pgms.common.api.PageResponse;
import com.pgms.common.api.SortValidator;
import com.pgms.common.exception.BusinessRuleException;
import com.pgms.common.exception.ConflictException;
import com.pgms.common.exception.NotFoundException;
import com.pgms.common.lookup.DisplayNames;
import com.pgms.common.util.ReferenceNumberGenerator;
import com.pgms.config.AppProperties;
import com.pgms.property.Property;
import com.pgms.property.PropertyRepository;
import com.pgms.room.Bed;
import com.pgms.room.BedRepository;
import com.pgms.security.AccessGuard;
import com.pgms.security.AccessScope;
import com.pgms.tenant.Tenant;
import com.pgms.tenant.TenantRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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

/**
 * Bed reservations. Lock ordering used across the booking module (prevents deadlocks):
 * tenant row -> booking row -> allocation row -> bed rows (ascending id).
 */
@Service
public class BookingService {

    private static final Set<String> SORTABLE = Set.of("createdAt", "expectedCheckInDate", "reservedUntil");
    private static final int MAX_DAYS_AHEAD = 60;

    private final BookingRepository bookingRepository;
    private final BedRepository bedRepository;
    private final TenantRepository tenantRepository;
    private final PropertyRepository propertyRepository;
    private final ReferenceNumberGenerator referenceNumbers;
    private final AccessGuard accessGuard;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final MeterRegistry meterRegistry;
    private final DisplayNames displayNames;
    private final Duration defaultHold;
    private final Clock clock;

    public BookingService(BookingRepository bookingRepository, BedRepository bedRepository, TenantRepository tenantRepository,
                          PropertyRepository propertyRepository, ReferenceNumberGenerator referenceNumbers,
                          AccessGuard accessGuard, AuditService auditService, ApplicationEventPublisher events,
                          MeterRegistry meterRegistry, DisplayNames displayNames, AppProperties properties, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.bedRepository = bedRepository;
        this.tenantRepository = tenantRepository;
        this.propertyRepository = propertyRepository;
        this.referenceNumbers = referenceNumbers;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
        this.events = events;
        this.meterRegistry = meterRegistry;
        this.displayNames = displayNames;
        this.defaultHold = properties.booking().reservationHold();
        this.clock = clock;
    }

    @Transactional
    public BookingResponse reserve(ReserveBedRequest request) {
        LocalDate today = LocalDate.now(clock);
        if (request.expectedCheckInDate().isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            throw new BusinessRuleException("CHECK_IN_TOO_FAR", "Reservations can be made at most " + MAX_DAYS_AHEAD + " days ahead");
        }
        Tenant tenant = tenantRepository.findByIdForUpdate(request.tenantId())
                .orElseThrow(() -> new NotFoundException("Tenant", request.tenantId()));
        accessGuard.requirePropertyAccess(tenant.getPropertyId());
        Bed bed = bedRepository.findByIdForUpdate(request.bedId()).orElseThrow(() -> new NotFoundException("Bed", request.bedId()));
        accessGuard.requirePropertyAccess(bed.getPropertyId());
        Property property = requireBookable(bed, tenant);

        if (tenant.isResident()) {
            throw new BusinessRuleException("TENANT_ALREADY_CHECKED_IN", "Tenant already occupies a bed; use a transfer instead");
        }
        if (bookingRepository.existsByTenantIdAndStatus(tenant.getId(), BookingStatus.RESERVED)) {
            throw new ConflictException("TENANT_ALREADY_RESERVED", "Tenant already has an active reservation");
        }
        bed.reserve(); // throws 409 unless the (locked, freshly read) bed is AVAILABLE

        Duration hold = request.holdHours() == null ? defaultHold : Duration.ofHours(request.holdHours());
        Instant reservedUntil = clock.instant().plus(hold);
        Booking booking = new Booking(referenceNumbers.next(ReferenceNumberGenerator.Kind.BOOKING),
                property.getOrganizationId(), property.getId(), tenant.getId(), bed.getId(), request.expectedCheckInDate(),
                reservedUntil, request.notes());
        booking.quote(bed.effectiveMonthlyRent(), bed.effectiveSecurityDeposit());
        bookingRepository.save(booking);
        tenant.relocateTo(property.getId());

        auditService.record(property.getOrganizationId(), "BED_RESERVED", "BOOKING", booking.getId(),
                Map.of("bedId", bed.getId(), "tenantId", tenant.getId(), "reservedUntil", reservedUntil.toString()));
        events.publishEvent(new BookingEvents.BedReserved(booking.getId(), booking.getBookingReference(),
                booking.getOrganizationId(), booking.getPropertyId(), tenant.getId(), bed.getId(),
                booking.getExpectedCheckInDate(), reservedUntil));
        meterRegistry.counter("pgms.bookings.reserved").increment();
        return respond(booking);
    }

    @Transactional
    public BookingResponse cancel(Long bookingId, String reason) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new NotFoundException("Booking", bookingId));
        accessGuard.requirePropertyAccess(booking.getPropertyId());
        release(booking, BookingStatus.CANCELLED, reason);
        return respond(booking);
    }

    /** Expires one reservation in its own transaction; a no-op if it was checked in or cancelled meanwhile. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expireIfDue(Long bookingId) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId).orElse(null);
        if (booking == null || booking.getStatus() != BookingStatus.RESERVED || booking.isLive(clock.instant())) {
            return false;
        }
        release(booking, BookingStatus.EXPIRED, null);
        meterRegistry.counter("pgms.bookings.expired").increment();
        return true;
    }

    @Transactional(readOnly = true)
    public BookingResponse get(Long id) {
        Booking booking = bookingRepository.findById(id).orElseThrow(() -> new NotFoundException("Booking", id));
        accessGuard.requirePropertyAccess(booking.getPropertyId());
        return respond(booking);
    }

    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> search(Long propertyId, Long tenantId, BookingStatus status, Pageable pageable) {
        AccessScope scope = accessGuard.scopeFor(propertyId);
        Pageable page = SortValidator.validate(pageable, SORTABLE, Sort.by(Sort.Direction.DESC, "createdAt"));
        if (scope.isEmpty()) {
            return PageResponse.from(Page.<Booking>empty(page), b -> BookingResponse.of(b, DisplayNames.Index.EMPTY));
        }
        Specification<Booking> spec = scope.toSpecification();
        if (tenantId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("tenantId"), tenantId));
        }
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        Page<Booking> bookings = bookingRepository.findAll(spec, page);
        DisplayNames.Index names = names(bookings.getContent());
        return PageResponse.from(bookings, b -> BookingResponse.of(b, names));
    }

    private BookingResponse respond(Booking booking) {
        return BookingResponse.of(booking, names(List.of(booking)));
    }

    /** Tenant names and bed/room codes for a page of bookings in two queries. */
    private DisplayNames.Index names(List<Booking> bookings) {
        return displayNames.lookup(DisplayNames.ids(bookings, Booking::getTenantId), Set.of(),
                DisplayNames.ids(bookings, Booking::getBedId));
    }

    private void release(Booking booking, BookingStatus outcome, String reason) {
        Bed bed = bedRepository.findByIdForUpdate(booking.getBedId())
                .orElseThrow(() -> new NotFoundException("Bed", booking.getBedId()));
        if (outcome == BookingStatus.CANCELLED) {
            booking.cancel(reason, clock.instant());
        } else {
            booking.expire();
        }
        bed.releaseReservation();
        auditService.record(booking.getOrganizationId(), "BOOKING_" + outcome.name(), "BOOKING", booking.getId(),
                reason == null ? Map.of() : Map.of("reason", reason));
        events.publishEvent(new BookingEvents.ReservationReleased(booking.getId(), booking.getBookingReference(),
                booking.getOrganizationId(), booking.getTenantId(), booking.getBedId(), outcome));
    }

    /** Shared eligibility rules for putting a tenant into a bed. */
    Property requireBookable(Bed bed, Tenant tenant) {
        Property property = propertyRepository.findById(bed.getPropertyId())
                .orElseThrow(() -> new NotFoundException("Property", bed.getPropertyId()));
        if (!property.getOrganizationId().equals(tenant.getOrganizationId())) {
            throw new NotFoundException("Bed", bed.getId());
        }
        if (!property.isOperational()) {
            throw new BusinessRuleException("PROPERTY_NOT_ACTIVE", "Property " + property.getCode() + " is " + property.getStatus());
        }
        if (!bed.getRoom().isActive()) {
            throw new BusinessRuleException("ROOM_NOT_ACTIVE", "Room " + bed.getRoom().getRoomNumber() + " is inactive");
        }
        return property;
    }
}
