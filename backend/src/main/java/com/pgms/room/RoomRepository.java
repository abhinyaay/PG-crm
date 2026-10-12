package com.pgms.room;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface RoomRepository extends JpaRepository<Room, Long>, JpaSpecificationExecutor<Room> {

    /** Fetch floor + building with the page to avoid lazy-loading them per row. */
    @Override
    @EntityGraph(attributePaths = {"floor", "floor.building"})
    Page<Room> findAll(Specification<Room> spec, Pageable pageable);
}
