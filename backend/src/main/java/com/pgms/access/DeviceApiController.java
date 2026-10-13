package com.pgms.access;

import com.pgms.access.AccessDtos.DeviceEventsRequest;
import com.pgms.access.AccessDtos.DeviceEventsResponse;
import com.pgms.access.AccessDtos.VerifyRequest;
import com.pgms.access.AccessDtos.VerifyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints for the local bridge (older / HTTP-only devices) and for door controllers. Authenticated with the
 * {@code X-Device-Key} shown once at registration, not with a user JWT.
 */
@RestController
@RequestMapping("/api/v1/access")
@Tag(name = "Biometric devices (bridge / door controller)")
public class DeviceApiController {

    private final DeviceIngestionService ingestion;

    public DeviceApiController(DeviceIngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @PostMapping("/device-events")
    @Operation(summary = "Push punches from a bridge; idempotent per device, device user id, time and direction")
    public DeviceEventsResponse deviceEvents(@RequestHeader(name = AccessDeviceService.KEY_HEADER, required = false) String key,
                                             @Valid @RequestBody DeviceEventsRequest request, HttpServletRequest http) {
        return ingestion.pushEvents(key, request, http.getRemoteAddr());
    }

    @PostMapping("/verify")
    @Operation(summary = "Door controller pre-check (dry run unless record=true): may this person pass now?")
    public VerifyResponse verify(@RequestHeader(name = AccessDeviceService.KEY_HEADER, required = false) String key,
                                 @Valid @RequestBody VerifyRequest request, HttpServletRequest http) {
        return ingestion.verify(key, request, http.getRemoteAddr());
    }
}
