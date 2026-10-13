package com.pgms.access;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AccessEventRepository extends JpaRepository<AccessEvent, Long>, JpaSpecificationExecutor<AccessEvent> {

    /** The idempotency key of device punches (same as {@code uk_access_events_device_punch}). */
    Optional<AccessEvent> findFirstByDeviceIdAndDeviceUserIdAndOccurredAtAndDirection(Long deviceId, String deviceUserId,
                                                                                     Instant occurredAt,
                                                                                     AccessDirection direction);

    long countByTenantIdAndDirectionAndResultAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
            Long tenantId, AccessDirection direction, AccessResult result, Instant from, Instant to);

    Optional<AccessEvent> findFirstByTenantIdAndPropertyIdAndResultOrderByOccurredAtDescIdDesc(Long tenantId, Long propertyId,
                                                                                             AccessResult result);

    Optional<AccessEvent> findFirstByUserIdAndPropertyIdAndResultOrderByOccurredAtDescIdDesc(Long userId, Long propertyId,
                                                                                           AccessResult result);

    Optional<AccessEvent> findFirstByTenantIdOrderByOccurredAtDescIdDesc(Long tenantId);
}
