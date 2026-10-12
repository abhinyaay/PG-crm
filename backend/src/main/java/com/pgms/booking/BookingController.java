package com.pgms.booking;

import com.pgms.booking.BookingDtos.BookingResponse;
import com.pgms.booking.BookingDtos.CancelBookingRequest;
import com.pgms.booking.BookingDtos.ReserveBedRequest;
import com.pgms.common.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bookings")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER')")
@Tag(name = "Bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    @Operation(summary = "Reserve an AVAILABLE bed for a prospective tenant (hold expires automatically)")
    public ResponseEntity<BookingResponse> reserve(@Valid @RequestBody ReserveBedRequest request) {
        BookingResponse booking = bookingService.reserve(request);
        return ResponseEntity.created(URI.create("/api/v1/bookings/" + booking.id())).body(booking);
    }

    @GetMapping
    public PageResponse<BookingResponse> search(@RequestParam(required = false) Long propertyId,
                                                @RequestParam(required = false) Long tenantId,
                                                @RequestParam(required = false) BookingStatus status,
                                                Pageable pageable) {
        return bookingService.search(propertyId, tenantId, status, pageable);
    }

    @GetMapping("/{id}")
    public BookingResponse get(@PathVariable Long id) {
        return bookingService.get(id);
    }

    @PostMapping("/{id}/cancel")
    public BookingResponse cancel(@PathVariable Long id, @Valid @RequestBody CancelBookingRequest request) {
        return bookingService.cancel(id, request.reason());
    }
}
