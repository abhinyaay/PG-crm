package com.pgms.complaint;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ComplaintCommentRepository extends JpaRepository<ComplaintComment, Long> {

    List<ComplaintComment> findByComplaintIdOrderByCreatedAtAscIdAsc(Long complaintId);

    List<ComplaintComment> findByComplaintIdAndInternalFalseOrderByCreatedAtAscIdAsc(Long complaintId);
}
