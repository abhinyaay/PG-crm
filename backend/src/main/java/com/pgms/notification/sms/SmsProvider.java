package com.pgms.notification.sms;

/**
 * One SMS gateway. Exactly one implementation is active, chosen by {@code app.notifications.sms.provider}
 * ({@code log}, {@code twilio} or {@code msg91}). Implementations throw
 * {@link com.pgms.notification.DeliveryException}: permanent for rejected requests, retryable for outages/timeouts.
 */
public interface SmsProvider {

    String name();

    void send(SmsMessage message);
}
