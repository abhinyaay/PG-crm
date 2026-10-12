package com.pgms.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development stand-in for an email provider ({@code app.notifications.email.provider=log}, the default): logs the
 * message instead of sending it. {@code smtp} switches to {@link com.pgms.notification.email.SmtpEmailSender}.
 */
@Component
@ConditionalOnProperty(name = "app.notifications.email.provider", havingValue = "log", matchIfMissing = true)
public class LoggingEmailSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public void send(Notification notification) {
        log.info("[email-stub] to={} type={} subject=\"{}\"", mask(notification.getRecipientAddress()),
                notification.getType(), notification.getTitle());
    }

    private static String mask(String email) {
        if (email == null || !email.contains("@")) {
            return "***";
        }
        int at = email.indexOf('@');
        return email.charAt(0) + "***" + email.substring(at);
    }
}
