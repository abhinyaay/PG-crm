package com.pgms.property;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PropertyAssignmentRepository extends JpaRepository<PropertyAssignment, Long> {

    boolean existsByPropertyIdAndUserId(Long propertyId, Long userId);

    Optional<PropertyAssignment> findByPropertyIdAndUserId(Long propertyId, Long userId);

    List<PropertyAssignment> findByPropertyId(Long propertyId);

    @Query("select pa.propertyId from PropertyAssignment pa where pa.userId = :userId")
    Set<Long> findPropertyIdsByUserId(@Param("userId") Long userId);

    void deleteByUserId(Long userId);
}
