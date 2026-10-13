package com.pgms.property;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FloorRepository extends JpaRepository<Floor, Long> {

    List<Floor> findByBuildingIdOrderByFloorNumber(Long buildingId);

    /** Floor plus its building in one select (the layout view needs the building code and property id). */
    @Query("select f from Floor f join fetch f.building where f.id = :id")
    Optional<Floor> findWithBuildingById(@Param("id") Long id);

    /**
     * {@code SELECT ... FOR UPDATE} on the floor row: every change to the geometry of the floor's spaces (bulk layout
     * update, placing a new space, resizing the grid) serialises here, so two editors can never each validate against
     * the other's stale positions and commit overlapping spaces. No join fetch, so only the floor row is locked.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Floor f where f.id = :id")
    Optional<Floor> findByIdForUpdate(@Param("id") Long id);
}
