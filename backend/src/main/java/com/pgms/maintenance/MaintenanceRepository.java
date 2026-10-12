package com.pgms.maintenance;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaintenanceRepository extends JpaRepository<MaintenanceRequest, Long>,
        JpaSpecificationExecutor<MaintenanceRequest> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MaintenanceRequest m where m.id = :id")
    Optional<MaintenanceRequest> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select m.id from MaintenanceRequest m
            where m.slaBreached = false and m.slaDueAt < :now
              and m.status in (com.pgms.maintenance.MaintenanceStatus.OPEN, com.pgms.maintenance.MaintenanceStatus.ASSIGNED,
                               com.pgms.maintenance.MaintenanceStatus.IN_PROGRESS)
            """)
    List<Long> findIdsBreachingSla(@Param("now") Instant now);
}
