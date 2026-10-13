package com.pgms.access;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccessCredentialRepository extends JpaRepository<AccessCredential, Long>,
        JpaSpecificationExecutor<AccessCredential> {

    Optional<AccessCredential> findFirstByPropertyIdAndDeviceUserIdAndStatus(Long propertyId, String deviceUserId,
                                                                            CredentialStatus status);

    boolean existsByPropertyIdAndDeviceUserIdAndStatus(Long propertyId, String deviceUserId, CredentialStatus status);

    List<AccessCredential> findByTenantIdAndStatus(Long tenantId, CredentialStatus status);

    /**
     * Highest numeric PIN ever used at the property, including revoked ones: a revoked PIN may still have a
     * fingerprint enrolled on the device, so suggested PINs are never reused.
     */
    @Query(value = """
            SELECT max(CAST(device_user_id AS BIGINT)) FROM access_credentials
            WHERE property_id = :propertyId AND device_user_id ~ '^[0-9]{1,15}$'
            """, nativeQuery = true)
    Long findMaxNumericPin(@Param("propertyId") Long propertyId);
}
