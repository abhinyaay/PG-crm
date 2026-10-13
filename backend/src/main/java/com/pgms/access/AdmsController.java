package com.pgms.access;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ZKTeco / eSSL "ADMS" (cloud server / push) protocol. The device itself calls these URLs over HTTP(S); there is no
 * JWT - a device is identified by its registered serial number ({@code SN}). Responses are the plain-text lines the
 * firmware expects. The body is read raw before any parameter parsing (some firmware sends a form content type
 * with tab-separated lines), and query parameters are parsed from the query string only.
 */
@RestController
@Tag(name = "Biometric devices (ADMS push)")
public class AdmsController {

    static final String UNKNOWN_DEVICE = "ERROR: UNKNOWN DEVICE";
    private static final String OK = "OK";

    private final DeviceIngestionService ingestion;

    public AdmsController(DeviceIngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @GetMapping({"/iclock/cdata", "/iclock/cdata.aspx"})
    @Operation(summary = "ADMS handshake: returns the option block (GET OPTION FROM: <SN> ...)")
    public ResponseEntity<String> handshake(HttpServletRequest request) {
        Map<String, String> query = query(request);
        return text(ingestion.handshake(query.get("sn"), request.getRemoteAddr()));
    }

    @PostMapping({"/iclock/cdata", "/iclock/cdata.aspx"})
    @Operation(summary = "ADMS upload: table=ATTLOG lines 'PIN<TAB>YYYY-MM-DD HH:MM:SS<TAB>status<TAB>verify...' -> 'OK: <n>'")
    public ResponseEntity<String> upload(HttpServletRequest request) throws IOException {
        String body = StreamUtils.copyToString(request.getInputStream(), StandardCharsets.UTF_8);
        Map<String, String> query = query(request);
        String table = query.getOrDefault("table", "");
        if ("ATTLOG".equalsIgnoreCase(table)) {
            DeviceIngestionService.AttLogOutcome outcome = ingestion.pushAttLog(query.get("sn"), query.get("stamp"), body,
                    request.getRemoteAddr());
            return text("OK: " + outcome.accepted());
        }
        ingestion.acknowledge(query.get("sn"), request.getRemoteAddr());
        return text(OK);
    }

    @GetMapping({"/iclock/getrequest", "/iclock/getrequest.aspx", "/iclock/ping"})
    @Operation(summary = "ADMS heartbeat; no commands are queued for devices")
    public ResponseEntity<String> heartbeat(HttpServletRequest request) {
        ingestion.heartbeat(query(request).get("sn"), request.getRemoteAddr());
        return text(OK);
    }

    @PostMapping({"/iclock/devicecmd", "/iclock/devicecmd.aspx"})
    @Operation(summary = "ADMS command results (acknowledged only)")
    public ResponseEntity<String> commandResult(HttpServletRequest request) throws IOException {
        StreamUtils.drain(request.getInputStream());
        ingestion.acknowledge(query(request).get("sn"), request.getRemoteAddr());
        return text(OK);
    }

    @ExceptionHandler(DeviceIngestionService.UnknownDeviceException.class)
    public ResponseEntity<String> unknownDevice() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).contentType(MediaType.TEXT_PLAIN).body(UNKNOWN_DEVICE);
    }

    private static ResponseEntity<String> text(String body) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(body);
    }

    /** Query-string parameters with lower-case names (firmware differs in SN / sn, Stamp / stamp). */
    static Map<String, String> query(HttpServletRequest request) {
        Map<String, String> params = new HashMap<>();
        String raw = request.getQueryString();
        if (raw == null) {
            return params;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                params.putIfAbsent(URLDecoder.decode(name, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT),
                        URLDecoder.decode(value, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                // malformed percent-encoding: ignore that parameter
                params.putIfAbsent(name.toLowerCase(Locale.ROOT), value);
            }
        }
        return params;
    }
}
