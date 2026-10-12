package com.pgms.complaint;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ComplaintActivityRepository extends JpaRepository<ComplaintActivity, Long> {

    List<ComplaintActivity> findByComplaintIdOrderByCreatedAtAscIdAsc(Long complaintId);
}
