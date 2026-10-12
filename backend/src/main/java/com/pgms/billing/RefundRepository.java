package com.pgms.billing;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    Optional<Refund> findByOrganizationIdAndIdempotencyKey(Long organizationId, String idempotencyKey);

    List<Refund> findByPaymentIdOrderByRefundedAt(Long paymentId);
}
