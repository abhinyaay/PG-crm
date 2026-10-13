package com.pgms.access;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccessDeviceRepository extends JpaRepository<AccessDevice, Long>, JpaSpecificationExecutor<AccessDevice> {

    /** {@code serial} must already be normalised (upper case). Soft-deleted devices are invisible. */
    Optional<AccessDevice> findBySerialNumber(String serial);

    boolean existsBySerialNumber(String serial);

    Optional<AccessDevice> findByApiKeyHash(String apiKeyHash);

    /**
     * Records that the device is alive. Bulk update (no version bump, so heartbeats never conflict with an admin
     * editing the device) and throttled: only writes when the last write is older than {@code threshold}, the IP
     * changed or an offline alert has to be cleared.
     */
    @Modifying
    @Query("""
            update AccessDevice d set d.lastSeenAt = :now, d.lastIp = :ip, d.offlineAlertedAt = null
            where d.id = :id and (d.lastSeenAt is null or d.lastSeenAt < :threshold or d.lastIp is null
                                  or d.lastIp <> :ip or d.offlineAlertedAt is not null)
            """)
    int touch(@Param("id") Long id, @Param("now") Instant now, @Param("ip") String ip, @Param("threshold") Instant threshold);

    @Modifying
    @Query("update AccessDevice d set d.attlogStamp = :stamp where d.id = :id")
    int updateAttlogStamp(@Param("id") Long id, @Param("stamp") String stamp);

    /** Claims the offline alert for the current outage; 0 when another run already alerted. */
    @Modifying
    @Query("update AccessDevice d set d.offlineAlertedAt = :at where d.id = :id and d.offlineAlertedAt is null")
    int markOfflineAlerted(@Param("id") Long id, @Param("at") Instant at);

    /**
     * ACTIVE devices of properties with the module switched on that were seen at least once and have been silent
     * longer than the property's {@code device_offline_minutes}, and were not alerted for this outage yet.
     */
    @Query(value = """
            SELECT d.id FROM access_devices d
            JOIN access_settings s ON s.property_id = d.property_id
            WHERE d.deleted_at IS NULL AND d.status = 'ACTIVE' AND s.enabled
              AND d.last_seen_at IS NOT NULL AND d.offline_alerted_at IS NULL
              AND d.last_seen_at < CAST(:now AS timestamptz) - make_interval(mins => s.device_offline_minutes)
            ORDER BY d.id
            """, nativeQuery = true)
    List<Long> findOfflineCandidateIds(@Param("now") Instant now);
}
