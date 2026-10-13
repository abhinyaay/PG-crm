package com.pgms.moveout;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MoveOutRepository extends JpaRepository<MoveOut, Long>, JpaSpecificationExecutor<MoveOut> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MoveOut m where m.id = :id")
    Optional<MoveOut> findByIdForUpdate(@Param("id") Long id);

    @Query("select m.tenantId from MoveOut m where m.id = :id")
    Optional<Long> findTenantIdById(@Param("id") Long id);

    @EntityGraph(attributePaths = "items")
    @Query("select m from MoveOut m where m.id = :id")
    Optional<MoveOut> findWithItems(@Param("id") Long id);

    /** The resident's open case (NOTICE_GIVEN / INSPECTED), locked; at most one by uk_move_outs_open_tenant. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select m from MoveOut m where m.tenantId = :tenantId
              and m.status in (com.pgms.moveout.MoveOutEnums.Status.NOTICE_GIVEN, com.pgms.moveout.MoveOutEnums.Status.INSPECTED)
            """)
    Optional<MoveOut> findOpenForTenantForUpdate(@Param("tenantId") Long tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select m from MoveOut m where m.depositAccountId = :accountId
              and m.status = com.pgms.moveout.MoveOutEnums.Status.SETTLED
            """)
    Optional<MoveOut> findSettledForDepositForUpdate(@Param("accountId") Long accountId);
}
