package com.pgms.access;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code app.access.*}: device ingestion tuning for the biometric module.
 *
 * @param maxClockSkew        punches further in the future than this are stored but flagged (device clock wrong)
 * @param alertMaxAge         limit alerts are only sent for punches younger than this (a device uploading an old
 *                            backlog must not page managers about last week)
 * @param heartbeatWriteInterval {@code last_seen_at} is written at most this often per device
 * @param unknownSerialLogInterval an unknown/disabled serial is logged at most once per this interval
 * @param deviceKeyFailuresPerWindow failed {@code X-Device-Key} attempts per client IP and minute before 429
 * @param maxAttlogLines      lines accepted from one ATTLOG upload (the rest is ignored; the device re-sends it)
 */
@Validated
@ConfigurationProperties(prefix = "app.access")
public record AccessProperties(
        @NotNull Duration maxClockSkew,
        @NotNull Duration alertMaxAge,
        @NotNull Duration heartbeatWriteInterval,
        @NotNull Duration unknownSerialLogInterval,
        @Min(1) int deviceKeyFailuresPerWindow,
        @Min(1) int maxAttlogLines,
        @Valid @NotNull Adms adms) {

    /**
     * Options sent to ZKTeco/eSSL devices in the ADMS handshake ({@code GET /iclock/cdata?options=all}).
     *
     * @param timeZone   value of the {@code TimeZone} option (330 = UTC+05:30 in minutes)
     * @param delay      seconds between {@code getrequest} heartbeats
     * @param errorDelay seconds the device waits before retrying after a failed request
     * @param transTimes fixed upload times ({@code HH:MM;HH:MM}) in addition to real-time push
     * @param transInterval minutes between batched uploads
     */
    public record Adms(int timeZone, @Min(1) int delay, @Min(1) int errorDelay, @NotBlank String transTimes,
                       @Min(1) int transInterval) {
    }
}
