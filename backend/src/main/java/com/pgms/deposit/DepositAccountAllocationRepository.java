package com.pgms.deposit;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DepositAccountAllocationRepository extends JpaRepository<DepositAccountAllocation, Long> {

    @Query("select m.allocationId from DepositAccountAllocation m where m.depositAccountId = :accountId order by m.allocationId")
    List<Long> findAllocationIds(@Param("accountId") Long accountId);
}
