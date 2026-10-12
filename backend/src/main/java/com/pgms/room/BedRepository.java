package com.pgms.room;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BedRepository extends JpaRepository<Bed, Long>, JpaSpecificationExecutor<Bed> {

    /**
     * {@code SELECT ... FOR UPDATE}: serialises every booking/check-in/transfer touching this bed. Concurrent requests
     * queue on the row lock (bounded by the connection's lock_timeout) and then re-read the committed status.
     * Deliberately no {@code join fetch b.room}: PostgreSQL's {@code FOR NO KEY UPDATE} would then lock the room row
     * too, serialising all beds of a room and breaking the ascending-bed-id lock order (deadlocks between transfers
     * inside one room). The room is loaded lazily, unlocked, when needed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Bed b where b.id = :id")
    Optional<Bed> findByIdForUpdate(@Param("id") Long id);

    @EntityGraph(attributePaths = {"room"})
    @Query("select b from Bed b where b.room.id = :roomId order by b.bedCode")
    List<Bed> findByRoomId(@Param("roomId") Long roomId);

    @EntityGraph(attributePaths = {"room"})
    @Query("select b from Bed b where b.room.id in :roomIds order by b.bedCode")
    List<Bed> findByRoomIdIn(@Param("roomIds") List<Long> roomIds);

    @Override
    @EntityGraph(attributePaths = {"room"})
    Page<Bed> findAll(Specification<Bed> spec, Pageable pageable);

    @Query("""
            select b.status as status, count(b) as total from Bed b
            where b.propertyId in :propertyIds group by b.status
            """)
    List<StatusCount> countByStatusForProperties(@Param("propertyIds") List<Long> propertyIds);

    @Query("""
            select b.status as status, count(b) as total from Bed b
            where (:organizationId is null or b.propertyId in
                    (select p.id from Property p where p.organizationId = :organizationId))
            group by b.status
            """)
    List<StatusCount> countByStatusForOrganization(@Param("organizationId") Long organizationId);

    interface StatusCount {
        BedStatus getStatus();

        long getTotal();
    }
}
