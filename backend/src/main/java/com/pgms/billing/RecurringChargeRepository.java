package com.pgms.billing;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecurringChargeRepository extends JpaRepository<RecurringCharge, Long> {

    List<RecurringCharge> findByTenantIdOrderByIdAsc(Long tenantId);

    List<RecurringCharge> findByTenantIdAndActiveTrueOrderByIdAsc(Long tenantId);
}
