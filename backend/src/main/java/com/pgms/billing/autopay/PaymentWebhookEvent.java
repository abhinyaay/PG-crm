package com.pgms.billing.autopay;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Receipt of one provider webhook delivery; {@code (provider, eventId)} is unique, so a replay is processed once. */
@Entity
@Table(name = "payment_webhook_events")
public class PaymentWebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String provider;

    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    protected PaymentWebhookEvent() {
    }

    public PaymentWebhookEvent(String provider, String eventId, String eventType, Instant receivedAt) {
        this.provider = provider;
        this.eventId = eventId;
        this.eventType = eventType.length() > 60 ? eventType.substring(0, 60) : eventType;
        this.receivedAt = receivedAt;
    }

    public Long getId() {
        return id;
    }
}
