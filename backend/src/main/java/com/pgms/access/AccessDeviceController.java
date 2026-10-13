package com.pgms.access;

import com.pgms.access.AccessDtos.CreateDeviceRequest;
import com.pgms.access.AccessDtos.DeviceKeyResponse;
import com.pgms.access.AccessDtos.DeviceResponse;
import com.pgms.access.AccessDtos.UpdateDeviceRequest;
import com.pgms.common.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/access/devices")
@Tag(name = "Access control")
public class AccessDeviceController {

    private final AccessDeviceService service;

    public AccessDeviceController(AccessDeviceService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(AccessSettingsController.MANAGERS)
    @Operation(summary = "Registered devices with online/offline status")
    public PageResponse<DeviceResponse> list(@RequestParam(required = false) Long propertyId,
                                             @RequestParam(required = false) DeviceStatus status, Pageable pageable) {
        return service.list(propertyId, status, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize(AccessSettingsController.MANAGERS)
    public DeviceResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize(AccessSettingsController.MANAGERS)
    @Operation(summary = "Register a device by serial number; the bridge key is returned once")
    public ResponseEntity<DeviceKeyResponse> create(@Valid @RequestBody CreateDeviceRequest request) {
        DeviceKeyResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/access/devices/" + created.device().id())).body(created);
    }

    @PatchMapping("/{id}")
    @PreAuthorize(AccessSettingsController.MANAGERS)
    public DeviceResponse update(@PathVariable Long id, @Valid @RequestBody UpdateDeviceRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/rotate-key")
    @PreAuthorize(AccessSettingsController.MANAGERS)
    @Operation(summary = "Issue a new bridge key (the old one stops working immediately); returned once")
    public DeviceKeyResponse rotateKey(@PathVariable Long id) {
        return service.rotateKey(id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(AccessSettingsController.MANAGERS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
