package com.pgms.access;

import com.pgms.access.AccessDtos.DeviceEventItem;
import com.pgms.access.AccessDtos.DeviceEventResult;
import com.pgms.access.AccessDtos.DeviceEventsRequest;
import com.pgms.access.AccessDtos.DeviceEventsResponse;
import com.pgms.access.AccessDtos.VerifyRequest;
import com.pgms.access.AccessDtos.VerifyResponse;
import com.pgms.common.exception.TooManyRequestsException;
import com.pgms.common.exception.UnauthorizedException;
import com.pgms.security.RedisRateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything a device (or a bridge / door controller speaking for one) sends: the ZKTeco/eSSL ADMS push protocol
 * identified by serial number, and the JSON endpoints authenticated with the device key. All ingestion is
 * idempotent - devices re-send logs after reconnects and stamp resets.
 */
@Service
public class DeviceIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DeviceIngestionService.class);
    private static final int MAX_TRACKED_UNKNOWN_SERIALS = 10_000;

    private final AccessDeviceRepository deviceRepository;
    private final AccessEventProcessor processor;
    private final RedisRateLimiter rateLimiter;
    private final AccessProperties config;
    private final Clock clock;
    /** serial → last time it was logged, so a misconfigured device retrying every few seconds cannot flood the log. */
    private final Map<String, Instant> unknownSerialLog = new ConcurrentHashMap<>();

    public DeviceIngestionService(AccessDeviceRepository deviceRepository, AccessEventProcessor processor,
                                  RedisRateLimiter rateLimiter, AccessProperties config, Clock clock) {
        this.deviceRepository = deviceRepository;
        this.processor = processor;
        this.rateLimiter = rateLimiter;
        this.config = config;
        this.clock = clock;
    }

    /** An ADMS request from a serial number that is not registered, was deleted or is disabled. */
    public static class UnknownDeviceException extends RuntimeException {
        public UnknownDeviceException(String serial) {
            super("Unknown or disabled device " + serial);
        }
    }

    /** Result of one ATTLOG upload; {@code accepted} (stored + duplicates) is what the device is told ("OK: n"). */
    public record AttLogOutcome(int accepted, int stored, int duplicates, int skipped) {
    }

    // ---- ADMS (serial number) ----

    /** {@code GET /iclock/cdata?SN=..&options=all}: the option block the device configures itself with. */
    @Transactional
    public String handshake(String serial, String ip) {
        AccessDevice device = requireSerial(serial, ip);
        touch(device, ip);
        AccessProperties.Adms adms = config.adms();
        String stamp = device.getAttlogStamp() == null ? "None" : device.getAttlogStamp();
        log.info("ADMS handshake from device {} ({}) at {}", device.getSerialNumber(), device.getName(), ip);
        return String.join("\n",
                "GET OPTION FROM: " + device.getSerialNumber(),
                "ATTLOGStamp=" + stamp,
                "OPERLOGStamp=9999",
                "ATTPHOTOStamp=None",
                "ErrorDelay=" + adms.errorDelay(),
                "Delay=" + adms.delay(),
                "TransTimes=" + adms.transTimes(),
                "TransInterval=" + adms.transInterval(),
                "TransFlag=TransData AttLog",
                "TimeZone=" + adms.timeZone(),
                "Realtime=1",
                "Encrypt=0") + "\n";
    }

    /** {@code GET /iclock/getrequest}: heartbeat. No commands are ever queued for devices. */
    @Transactional
    public void heartbeat(String serial, String ip) {
        touch(requireSerial(serial, ip), ip);
    }

    /** Uploads of tables we do not use (OPERLOG, ATTPHOTO, user/fingerprint info, command results): acknowledged only. */
    @Transactional
    public void acknowledge(String serial, String ip) {
        touch(requireSerial(serial, ip), ip);
    }

    /** {@code POST /iclock/cdata?SN=..&table=ATTLOG}: one punch per line; malformed lines are skipped. */
    @Transactional
    public AttLogOutcome pushAttLog(String serial, String stamp, String body, String ip) {
        AccessDevice device = requireSerial(serial, ip);
        processor.lockDevice(device.getId());
        AttLogParser.Result parsed = AttLogParser.parse(body, device.zone(), config.maxAttlogLines());
        int stored = 0;
        int duplicates = 0;
        for (AttLogParser.Punch punch : parsed.punches()) {
            AccessDirection direction = AccessRules.resolveDirection(device.getDirection(), null, punch.status());
            AccessMethod method = AccessRules.fromVerifyMode(punch.verify());
            AccessEventProcessor.Recorded recorded = processor.recordDevicePunch(device, punch.pin(), direction, method,
                    punch.occurredAt());
            if (recorded.duplicate()) {
                duplicates++;
            } else {
                stored++;
            }
        }
        if (stamp != null && !stamp.isBlank() && stamp.length() <= 32) {
            deviceRepository.updateAttlogStamp(device.getId(), stamp.trim());
        }
        touch(device, ip);
        if (parsed.skipped() > 0) {
            log.warn("Device {} ATTLOG upload: skipped {} malformed line(s) {}", device.getSerialNumber(), parsed.skipped(),
                    parsed.errors());
        }
        log.info("Device {} ATTLOG upload: {} stored, {} duplicate(s), {} skipped", device.getSerialNumber(), stored, duplicates,
                parsed.skipped());
        return new AttLogOutcome(stored + duplicates, stored, duplicates, parsed.skipped());
    }

    // ---- bridge / door controller (device key) ----

    @Transactional
    public DeviceEventsResponse pushEvents(String key, DeviceEventsRequest request, String ip) {
        AccessDevice device = requireKey(key, ip);
        processor.lockDevice(device.getId());
        List<DeviceEventResult> results = new ArrayList<>();
        int stored = 0;
        int duplicates = 0;
        int rejected = 0;
        for (int i = 0; i < request.events().size(); i++) {
            DeviceEventItem item = request.events().get(i);
            AccessMethod method = item.method() == null ? AccessMethod.FINGERPRINT : item.method();
            AccessDirection direction = AccessRules.resolveDirection(device.getDirection(), item.direction(), null);
            Instant occurredAt;
            try {
                occurredAt = parseTime(item.occurredAt(), device.zone());
            } catch (DateTimeParseException e) {
                rejected++;
                results.add(new DeviceEventResult(i, item.deviceUserId(), "REJECTED", null, null, direction, null,
                        "occurredAt is not an ISO-8601 date-time"));
                continue;
            }
            if (!method.isDevice()) {
                rejected++;
                results.add(new DeviceEventResult(i, item.deviceUserId(), "REJECTED", null, null, direction, occurredAt,
                        "method must be FINGERPRINT, FACE or CARD"));
                continue;
            }
            AccessEventProcessor.Recorded recorded = processor.recordDevicePunch(device, item.deviceUserId().trim(), direction,
                    method, occurredAt);
            if (recorded.duplicate()) {
                duplicates++;
            } else {
                stored++;
            }
            results.add(new DeviceEventResult(i, item.deviceUserId(), recorded.duplicate() ? "DUPLICATE" : "STORED",
                    recorded.event().getId(), recorded.event().getResult(), direction, occurredAt, null));
        }
        touch(device, ip);
        return new DeviceEventsResponse(device.getId(), stored, duplicates, rejected, results);
    }

    /**
     * Door-controller pre-check: may this person pass now? A dry run unless {@code record} is set, in which case the
     * decision is also stored as an event (so the controller does not have to push it separately).
     */
    @Transactional
    public VerifyResponse verify(String key, VerifyRequest request, String ip) {
        AccessDevice device = requireKey(key, ip);
        touch(device, ip);
        String pin = request.deviceUserId().trim();
        AccessDirection direction = AccessRules.resolveDirection(device.getDirection(), request.direction(), null);
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        AccessSettings settings = processor.settingsFor(device.getPropertyId());
        AccessEventProcessor.Subject subject = processor.resolve(settings, device.getOrganizationId(), device.getPropertyId(), pin);
        if (Boolean.TRUE.equals(request.record())) {
            processor.lockDevice(device.getId());
            AccessEventProcessor.Recorded recorded = processor.recordDevicePunch(device, pin, direction, AccessMethod.FINGERPRINT,
                    now);
            AccessEventProcessor.Decision decision = recorded.decision() != null ? recorded.decision()
                    : new AccessEventProcessor.Decision(recorded.event().getResult(), recorded.event().getReason(), 0, null);
            return new VerifyResponse(decision.allowed(), decision.result(), decision.reason(), subject.name(),
                    decision.entriesToday(), decision.limit(), direction, recorded.event().getId());
        }
        AccessEventProcessor.Decision decision = processor.decide(settings, subject, direction, AccessMethod.FINGERPRINT, now, false);
        return new VerifyResponse(decision.allowed(), decision.result(), decision.reason(), subject.name(), decision.entriesToday(),
                decision.limit(), direction, null);
    }

    // ---- helpers ----

    private AccessDevice requireSerial(String serial, String ip) {
        String normalized = AccessDevice.normalizeSerial(serial);
        Optional<AccessDevice> device = normalized == null || normalized.isEmpty()
                ? Optional.empty() : deviceRepository.findBySerialNumber(normalized);
        if (device.isEmpty() || !device.get().isActive()) {
            logUnknown(normalized == null ? "<none>" : normalized, ip, device.isPresent());
            throw new UnknownDeviceException(normalized);
        }
        return device.get();
    }

    private AccessDevice requireKey(String key, String ip) {
        Optional<AccessDevice> device = key == null || key.isBlank()
                ? Optional.empty() : deviceRepository.findByApiKeyHash(DeviceKeys.hash(key));
        if (device.isPresent() && device.get().isActive()) {
            return device.get();
        }
        RedisRateLimiter.Decision throttle = rateLimiter.hit("device-key", ip == null ? "unknown" : ip,
                config.deviceKeyFailuresPerWindow(), Duration.ofMinutes(1));
        log.warn("Rejected device request with {} device key from {}", device.isPresent() ? "a disabled" : "an invalid", ip);
        if (!throttle.allowed()) {
            throw new TooManyRequestsException("Too many invalid device keys. Try again in " + throttle.retryAfterSeconds() + "s");
        }
        throw new UnauthorizedException("INVALID_DEVICE_KEY", "Missing, invalid or disabled device key");
    }

    private void touch(AccessDevice device, String ip) {
        Instant now = clock.instant();
        deviceRepository.touch(device.getId(), now, ip == null ? "" : ip, now.minus(config.heartbeatWriteInterval()));
    }

    private void logUnknown(String serial, String ip, boolean disabled) {
        Instant now = clock.instant();
        if (unknownSerialLog.size() > MAX_TRACKED_UNKNOWN_SERIALS) {
            unknownSerialLog.clear();
        }
        Instant last = unknownSerialLog.get(serial);
        if (last == null || last.isBefore(now.minus(config.unknownSerialLogInterval()))) {
            unknownSerialLog.put(serial, now);
            log.warn("Rejected ADMS request from {} device serial={} ip={} - register it under Settings > Biometric devices",
                    disabled ? "disabled" : "unregistered", serial, ip);
        }
    }

    /** ISO instant / offset date-time, or a local date-time ('T' or space separated) in the device's zone. */
    static Instant parseTime(String value, ZoneId deviceZone) {
        String text = value.trim();
        Instant instant;
        try {
            instant = OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            instant = LocalDateTime.parse(text.replace(' ', 'T')).atZone(deviceZone).toInstant();
        }
        return instant.truncatedTo(ChronoUnit.SECONDS);
    }
}
