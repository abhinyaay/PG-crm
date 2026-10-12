package com.pgms.auth;

import java.time.Duration;
import java.time.Instant;

/** Domain events of the auth module; the notification module turns them into (mandatory) messages. */
public final class AuthEvents {

    private AuthEvents() {
    }

    /** What a one-time code is for. */
    public enum OtpPurpose {
        /** "Forgot password". */
        RESET,
        /** First password of a new portal account. */
        SETUP
    }

    /** Which channel the code goes to: the one matching the identifier the user typed, or both for a new account. */
    public enum OtpDelivery {
        EMAIL,
        SMS,
        EMAIL_AND_SMS
    }

    /**
     * A one-time code was issued. The plain code travels only in this in-memory event and in the outbox row that
     * delivers it (which is redacted once delivery is settled).
     */
    public record OtpIssued(Long userId, Long organizationId, OtpPurpose purpose, OtpDelivery delivery, String code,
                            Duration validity) {

        @Override
        public String toString() {
            return "OtpIssued[userId=" + userId + ", purpose=" + purpose + ", delivery=" + delivery + "]";
        }
    }

    /** {@code reason}: CHANGED (by the user), RESET (via one-time code) or ADMIN_RESET. */
    public record PasswordChanged(Long userId, Long organizationId, Instant at, String reason) {
    }
}
