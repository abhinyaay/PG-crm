package com.pgms.booking;

import com.pgms.booking.BookingDtos.AllocationResponse;
import com.pgms.booking.BookingDtos.CheckInRequest;
import com.pgms.booking.BookingDtos.CheckInResponse;
import com.pgms.booking.BookingDtos.CheckOutRequest;
import com.pgms.booking.BookingDtos.CheckOutResponse;
import com.pgms.booking.BookingDtos.NoticeRequest;
import com.pgms.booking.BookingDtos.TransferRequest;
import com.pgms.booking.BookingDtos.TransferResponse;
import com.pgms.common.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
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
@RequestMapping("/api/v1")
@Tag(name = "Allocations (check-in / check-out / transfer)")
public class AllocationController {

    private static final String OPERATORS = "hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER')";

    private final AllocationService allocationService;

    public AllocationController(AllocationService allocationService) {
        this.allocationService = allocationService;
    }

    @PostMapping("/allocations/check-in")
    @PreAuthorize(OPERATORS)
    @Operation(summary = "Check a tenant into a bed (against a reservation or walk-in); issues deposit + first rent invoice")
    public ResponseEntity<CheckInResponse> checkIn(@Valid @RequestBody CheckInRequest request) {
        CheckInResponse response = allocationService.checkIn(request);
        return ResponseEntity.created(URI.create("/api/v1/allocations/" + response.allocation().id())).body(response);
    }

    @PostMapping("/allocations/{id}/check-out")
    @PreAuthorize(OPERATORS)
    @Operation(summary = "Check a tenant out; frees the bed and returns the deposit settlement")
    public CheckOutResponse checkOut(@PathVariable Long id, @Valid @RequestBody CheckOutRequest request) {
        return allocationService.checkOut(id, request);
    }

    @PostMapping("/allocations/{id}/transfer")
    @PreAuthorize(OPERATORS)
    @Operation(summary = "Move a tenant to another bed/room/property")
    public TransferResponse transfer(@PathVariable Long id, @Valid @RequestBody TransferRequest request) {
        return allocationService.transfer(id, request);
    }

    @PostMapping("/allocations/{id}/notice")
    @PreAuthorize(OPERATORS)
    public AllocationResponse giveNotice(@PathVariable Long id, @Valid @RequestBody NoticeRequest request) {
        return allocationService.giveNotice(id, request.expectedCheckOutDate());
    }

    @PostMapping("/allocations/{id}/notice/withdraw")
    @PreAuthorize(OPERATORS)
    public AllocationResponse withdrawNotice(@PathVariable Long id) {
        return allocationService.withdrawNotice(id);
    }

    @GetMapping("/allocations")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER','STAFF')")
    @Operation(summary = "Search allocations; expectedEndBefore lists upcoming check-outs")
    public PageResponse<AllocationResponse> search(@RequestParam(required = false) Long propertyId,
                                                   @RequestParam(required = false) Long tenantId,
                                                   @RequestParam(required = false) Long bedId,
                                                   @RequestParam(required = false) AllocationStatus status,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                                   LocalDate expectedEndBefore,
                                                   Pageable pageable) {
        return allocationService.search(propertyId, tenantId, bedId, status, expectedEndBefore, pageable);
    }

    @GetMapping("/allocations/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER','STAFF','TENANT')")
    public AllocationResponse get(@PathVariable Long id) {
        return allocationService.get(id);
    }

    @GetMapping("/tenants/{tenantId}/allocations")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER','STAFF','TENANT')")
    @Operation(summary = "Stay history of a tenant, including transfers")
    public List<AllocationResponse> tenantHistory(@PathVariable Long tenantId) {
        return allocationService.tenantHistory(tenantId);
    }

    @GetMapping("/beds/{bedId}/allocations")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN','MANAGER','STAFF')")
    public List<AllocationResponse> bedHistory(@PathVariable Long bedId) {
        return allocationService.bedHistory(bedId);
    }
}
