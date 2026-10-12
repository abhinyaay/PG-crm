package com.pgms.notification;

/**
 * Thrown by a {@link NotificationSender} when a message could not be delivered. {@code permanent} failures (invalid
 * recipient, rejected request, missing template) are marked FAILED at once; everything else is retried with backoff.
 */
public class DeliveryException extends RuntimeException {

    private final boolean permanent;

    public DeliveryException(String message, boolean permanent) {
        super(message);
        this.permanent = permanent;
    }

    public DeliveryException(String message, boolean permanent, Throwable cause) {
        super(message, cause);
        this.permanent = permanent;
    }

    public static DeliveryException permanent(String message) {
        return new DeliveryException(message, true);
    }

    public static DeliveryException retryable(String message, Throwable cause) {
        return new DeliveryException(message, false, cause);
    }

    public boolean isPermanent() {
        return permanent;
    }
}
