package com.pgms.maintenance;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaintenanceStatusHistoryRepository extends JpaRepository<MaintenanceStatusHistory, Long> {

    List<MaintenanceStatusHistory> findByRequestIdOrderByChangedAtAscIdAsc(Long requestId);
}
