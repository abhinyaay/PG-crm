package com.pgms.booking;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, Long>, JpaSpecificationExecutor<Booking> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") Long id);

    boolean existsByTenantIdAndStatus(Long tenantId, BookingStatus status);

    @Query("select b.id from Booking b where b.status = com.pgms.booking.BookingStatus.RESERVED and b.reservedUntil <= :now "
            + "order by b.reservedUntil")
    List<Long> findExpiredReservationIds(@Param("now") Instant now, Pageable pageable);
}
