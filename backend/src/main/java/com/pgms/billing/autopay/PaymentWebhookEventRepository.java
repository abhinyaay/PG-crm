package com.pgms.billing.autopay;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentWebhookEventRepository extends JpaRepository<PaymentWebhookEvent, Long> {

    boolean existsByProviderAndEventId(String provider, String eventId);
}
