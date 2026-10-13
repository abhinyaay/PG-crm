package com.pgms.access;

import com.pgms.access.AccessDtos.CreateDeviceRequest;
import com.pgms.access.AccessDtos.DeviceKeyResponse;
import com.pgms.access.AccessDtos.DeviceResponse;
import com.pgms.access.AccessDtos.DeviceSetup;
import com.pgms.access.AccessDtos.UpdateDeviceRequest;
import com.pgms.audit.AuditService;
import com.pgms.common.api.PageResponse;
import com.pgms.common.api.SortValidator;
import com.pgms.common.exception.BadRequestException;
import com.pgms.common.exception.ConflictException;
import com.pgms.common.exception.NotFoundException;
import com.pgms.property.PropertyRepository;
import com.pgms.security.AccessGuard;
import com.pgms.security.AccessScope;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registration and lifecycle of gate terminals. */
@Service
public class AccessDeviceService {

    static final String ADMS_PATH = "/iclock/cdata";
    static final String DEVICE_EVENTS_PATH = "/api/v1/access/device-events";
    static final String VERIFY_PATH = "/api/v1/access/verify";
    static final String KEY_HEADER = "X-Device-Key";
    private static final Set<String> SORTABLE = Set.of("name", "serialNumber", "createdAt", "lastSeenAt");

    private final AccessDeviceRepository deviceRepository;
    private final PropertyRepository propertyRepository;
    private final AccessEventProcessor processor;
    private final AccessGuard accessGuard;
    private final AuditService auditService;
    private final Clock clock;

    public AccessDeviceService(AccessDeviceRepository deviceRepository, PropertyRepository propertyRepository,
                               AccessEventProcessor processor, AccessGuard accessGuard, AuditService auditService, Clock clock) {
        this.deviceRepository = deviceRepository;
        this.propertyRepository = propertyRepository;
        this.processor = processor;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<DeviceResponse> list(Long propertyId, DeviceStatus status, Pageable pageable) {
        AccessScope scope = accessGuard.scopeFor(propertyId);
        Pageable page = SortValidator.validate(pageable, SORTABLE, Sort.by("name"));
        if (scope.isEmpty()) {
            return PageResponse.from(Page.<AccessDevice>empty(page), d -> toResponse(d, new HashMap<>()));
        }
        Specification<AccessDevice> spec = scope.toSpecification();
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        Map<Long, Integer> offlineMinutes = new HashMap<>();
        return PageResponse.from(deviceRepository.findAll(spec, page), d -> toResponse(d, offlineMinutes));
    }

    @Transactional(readOnly = true)
    public DeviceResponse get(Long id) {
        return toResponse(loadAccessible(id), new HashMap<>());
    }

    @Transactional
    public DeviceKeyResponse create(CreateDeviceRequest request) {
        accessGuard.requirePropertyAccess(request.propertyId());
        Long organizationId = propertyRepository.findOrganizationIdById(request.propertyId())
                .orElseThrow(() -> new NotFoundException("Property", request.propertyId()));
        String serial = AccessDevice.normalizeSerial(request.serialNumber());
        if (deviceRepository.existsBySerialNumber(serial)) {
            throw new ConflictException("DEVICE_SERIAL_EXISTS", "A device with this serial number is already registered");
        }
        String key = DeviceKeys.generate();
        AccessDevice device = deviceRepository.saveAndFlush(new AccessDevice(organizationId, request.propertyId(),
                request.name().trim(), request.vendor(), serial, request.direction(), zone(request.timeZone()),
                DeviceKeys.hash(key)));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("propertyId", device.getPropertyId());
        details.put("serialNumber", serial);
        details.put("vendor", device.getVendor().name());
        details.put("direction", device.getDirection().name());
        auditService.record(organizationId, "ACCESS_DEVICE_REGISTERED", "ACCESS_DEVICE", device.getId(), details);
        return withKey(device, key);
    }

    @Transactional
    public DeviceResponse update(Long id, UpdateDeviceRequest request) {
        AccessDevice device = loadAccessible(id);
        device.update(request.name() == null ? null : request.name().trim(), request.direction(), request.status(),
                request.timeZone() == null ? null : zone(request.timeZone()));
        deviceRepository.saveAndFlush(device);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", device.getName());
        details.put("direction", device.getDirection().name());
        details.put("status", device.getStatus().name());
        details.put("timeZone", device.getTimeZone());
        auditService.record(device.getOrganizationId(), "ACCESS_DEVICE_UPDATED", "ACCESS_DEVICE", id, details);
        return toResponse(device, new HashMap<>());
    }

    @Transactional
    public DeviceKeyResponse rotateKey(Long id) {
        AccessDevice device = loadAccessible(id);
        String key = DeviceKeys.generate();
        device.rotateKey(DeviceKeys.hash(key));
        deviceRepository.saveAndFlush(device);
        auditService.record(device.getOrganizationId(), "ACCESS_DEVICE_KEY_ROTATED", "ACCESS_DEVICE", id);
        return withKey(device, key);
    }

    @Transactional
    public void delete(Long id) {
        AccessDevice device = loadAccessible(id);
        device.softDelete(clock.instant());
        auditService.record(device.getOrganizationId(), "ACCESS_DEVICE_DELETED", "ACCESS_DEVICE", id,
                Map.of("serialNumber", device.getSerialNumber()));
    }

    private AccessDevice loadAccessible(Long id) {
        AccessDevice device = deviceRepository.findById(id).orElseThrow(() -> new NotFoundException("Device", id));
        accessGuard.requirePropertyAccess(device.getPropertyId());
        return device;
    }

    private DeviceKeyResponse withKey(AccessDevice device, String key) {
        return new DeviceKeyResponse(toResponse(device, new HashMap<>()), key, new DeviceSetup(ADMS_PATH, DEVICE_EVENTS_PATH,
                VERIFY_PATH, KEY_HEADER, device.getSerialNumber(), device.getTimeZone()));
    }

    private DeviceResponse toResponse(AccessDevice device, Map<Long, Integer> offlineMinutesByProperty) {
        int minutes = offlineMinutesByProperty.computeIfAbsent(device.getPropertyId(),
                p -> processor.settingsFor(p).getDeviceOfflineMinutes());
        Instant now = clock.instant();
        return DeviceResponse.of(device, device.isActive() && device.isOnline(now, minutes));
    }

    private ZoneId zone(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) {
            return processor.zone();
        }
        try {
            return ZoneId.of(timeZone.trim());
        } catch (DateTimeException e) {
            throw new BadRequestException("INVALID_TIME_ZONE", "Unknown time zone '" + timeZone + "', e.g. Asia/Kolkata");
        }
    }
}
