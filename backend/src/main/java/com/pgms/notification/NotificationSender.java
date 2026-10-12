package com.pgms.notification;

/**
 * Strategy for an external delivery channel. Adding SMS (e.g. SNS/Twilio) or push (FCM) means adding one
 * implementation bean; the outbox, retry and dedup logic stay unchanged.
 */
public interface NotificationSender {

    NotificationChannel channel();

    /** Deliver or throw; a thrown exception schedules a retry with backoff. */
    void send(Notification notification);
}
