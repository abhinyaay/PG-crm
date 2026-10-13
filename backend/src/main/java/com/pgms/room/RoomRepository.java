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

public interface RoomRepository extends JpaRepository<Room, Long>, JpaSpecificationExecutor<Room> {

    /** Fetch floor + building with the page to avoid lazy-loading them per row. */
    @Override
    @EntityGraph(attributePaths = {"floor", "floor.building"})
    Page<Room> findAll(Specification<Room> spec, Pageable pageable);

    /**
     * {@code SELECT ... FOR UPDATE} on the room: adding/removing individual beds serialises here so the room's
     * capacity always equals its active bed count, however many editors add beds at the same time.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Room r where r.id = :id")
    Optional<Room> findByIdForUpdate(@Param("id") Long id);

    /** All (non-deleted) spaces of a floor - the layout view and geometry validation work on the whole floor. */
    @Query("select r from Room r where r.floor.id = :floorId order by r.roomNumber, r.id")
    List<Room> findByFloorId(@Param("floorId") Long floorId);
}
