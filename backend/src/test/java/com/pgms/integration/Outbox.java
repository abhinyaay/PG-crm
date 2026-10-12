package com.pgms.integration;

import com.pgms.notification.NotificationDispatcher;
import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Test helpers around the notification outbox and the fake providers (GreenMail, {@link SmsGatewayStub}). */
@Component
public class Outbox {

    private static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");

    private final NotificationDispatcher dispatcher;
    private final JdbcTemplate jdbc;

    public Outbox(NotificationDispatcher dispatcher, JdbcTemplate jdbc) {
        this.dispatcher = dispatcher;
        this.jdbc = jdbc;
    }

    /** Runs dispatcher rounds until nothing is due (rows of other tests are delivered too; that is harmless). */
    public void dispatchAll() {
        for (int round = 0; round < 100 && dispatcher.dispatchBatch() > 0; round++) {
            // keep going
        }
    }

    public List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }

    public SmsGatewayStub sms() {
        return AbstractIntegrationTest.SMS_GATEWAY;
    }

    public List<MimeMessage> emailsTo(String address) {
        return Arrays.stream(AbstractIntegrationTest.SMTP.getReceivedMessages()).filter(m -> {
            try {
                return Arrays.stream(m.getAllRecipients()).anyMatch(a -> a.toString().equalsIgnoreCase(address));
            } catch (MessagingException e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    /** text/plain and text/html bodies of a (multipart) message, keyed by lower-case base content type. */
    public static Map<String, String> textParts(Part part) {
        Map<String, String> parts = new LinkedHashMap<>();
        collect(part, parts);
        return parts;
    }

    public static String codeIn(String text) {
        Matcher matcher = SIX_DIGITS.matcher(text);
        if (!matcher.find()) {
            throw new IllegalStateException("No 6-digit code in: " + text);
        }
        return matcher.group(1);
    }

    private static void collect(Part part, Map<String, String> parts) {
        try {
            Object content = part.getContent();
            if (content instanceof Multipart multipart) {
                for (int i = 0; i < multipart.getCount(); i++) {
                    BodyPart child = multipart.getBodyPart(i);
                    collect(child, parts);
                }
            } else if (content instanceof String text) {
                parts.put(part.getContentType().split(";")[0].trim().toLowerCase(Locale.ROOT), text);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
