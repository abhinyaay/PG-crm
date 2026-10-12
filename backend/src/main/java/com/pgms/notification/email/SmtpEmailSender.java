package com.pgms.notification.email;

import com.pgms.config.AppProperties;
import com.pgms.notification.DeliveryException;
import com.pgms.notification.Notification;
import com.pgms.notification.NotificationChannel;
import com.pgms.notification.NotificationSender;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * EMAIL channel over SMTP (Mailpit locally, Amazon SES SMTP in production; connection settings are the standard
 * {@code spring.mail.*} ones). Sends multipart/alternative messages: plain text plus the branded HTML layout.
 */
@Component
@ConditionalOnProperty(name = "app.notifications.email.provider", havingValue = "smtp")
public class SmtpEmailSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender mailSender;
    private final EmailTemplateRenderer renderer;
    private final AppProperties.Email config;

    public SmtpEmailSender(JavaMailSender mailSender, EmailTemplateRenderer renderer, AppProperties properties) {
        this.mailSender = mailSender;
        this.renderer = renderer;
        this.config = properties.notifications().email();
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public void send(Notification notification) {
        EmailTemplateRenderer.RenderedEmail email = renderer.render(notification.getTitle(), notification.getBody(),
                notification.getParams());
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
                    StandardCharsets.UTF_8.name());
            helper.setFrom(new InternetAddress(config.from(), config.fromName(), StandardCharsets.UTF_8.name()));
            helper.setTo(new InternetAddress(notification.getRecipientAddress(), true));
            if (config.replyTo() != null && !config.replyTo().isBlank()) {
                helper.setReplyTo(config.replyTo());
            }
            helper.setSubject(email.subject());
            helper.setText(email.text(), email.html());
            message.setHeader("X-PGMS-Notification-Id", String.valueOf(notification.getId()));
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw DeliveryException.permanent("Invalid email message: " + e.getMessage());
        }
        try {
            mailSender.send(message);
        } catch (MailException e) {
            throw classify(e);
        }
    }

    /** Malformed messages and recipients the server rejected are permanent; connection/auth problems are retried. */
    static DeliveryException classify(MailException e) {
        if (e instanceof MailParseException || e instanceof MailPreparationException) {
            return new DeliveryException("Email could not be prepared: " + e.getMessage(), true, e);
        }
        if (e instanceof MailAuthenticationException) {
            log.error("SMTP authentication failed - check SPRING_MAIL_USERNAME / SPRING_MAIL_PASSWORD");
            return DeliveryException.retryable("SMTP authentication failed", e);
        }
        if (e instanceof MailSendException send) {
            for (Exception cause : send.getMessageExceptions()) {
                if (rejectedRecipient(cause)) {
                    return new DeliveryException("Recipient rejected by the mail server: " + cause.getMessage(), true, e);
                }
            }
            if (rejectedRecipient(e.getCause())) {
                return new DeliveryException("Recipient rejected by the mail server", true, e);
            }
        }
        return DeliveryException.retryable("SMTP delivery failed: " + e.getMessage(), e);
    }

    private static boolean rejectedRecipient(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof SendFailedException failed && failed.getInvalidAddresses() != null
                    && failed.getInvalidAddresses().length > 0) {
                return true;
            }
        }
        return false;
    }
}
