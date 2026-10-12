package com.pgms.complaint;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ComplaintRepository extends JpaRepository<Complaint, Long>, JpaSpecificationExecutor<Complaint> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Complaint c where c.id = :id")
    Optional<Complaint> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select c.id from Complaint c
            where c.dueAt < :now and c.escalationLevel < :maxLevel
              and c.status in (com.pgms.complaint.ComplaintStatus.OPEN, com.pgms.complaint.ComplaintStatus.IN_PROGRESS,
                               com.pgms.complaint.ComplaintStatus.ESCALATED)
            """)
    List<Long> findIdsDueForEscalation(@Param("now") Instant now, @Param("maxLevel") int maxLevel);
}
