package com.pgms.notification.sms;

import com.pgms.config.AppProperties;
import com.pgms.notification.NotificationType;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Fixed SMS templates from {@code app.notifications.sms.templates.<TYPE>}. SMS text is produced only from these
 * (registered DLT templates in India) - never from the free-form notification body. Variable values are flattened to
 * one line and capped at the DLT variable length, so a value can never change the shape of the message.
 */
@Component
public class SmsTemplates {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\s]+");

    private final Map<NotificationType, AppProperties.SmsTemplate> templates = new EnumMap<>(NotificationType.class);
    private final int maxVariableLength;

    @Autowired
    public SmsTemplates(AppProperties properties) {
        this(properties.notifications().sms().templates(), properties.notifications().sms().maxVariableLength());
    }

    public SmsTemplates(Map<String, AppProperties.SmsTemplate> configured, int maxVariableLength) {
        this.maxVariableLength = maxVariableLength;
        if (configured != null) {
            configured.forEach((key, template) -> {
                NotificationType type;
                try {
                    type = NotificationType.valueOf(key.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException("Unknown notification type in app.notifications.sms.templates: " + key, e);
                }
                if (template != null && template.text() != null && !template.text().isBlank()) {
                    templates.put(type, template);
                }
            });
        }
    }

    /** A rendered template: text for "send text" gateways, id + variables for DLT flow gateways. */
    public record RenderedSms(String dltTemplateId, String text, Map<String, String> variables) {
    }

    public boolean hasTemplate(NotificationType type) {
        return templates.containsKey(type);
    }

    /** Empty when the type has no template or a variable is missing. */
    public Optional<RenderedSms> render(NotificationType type, Map<String, String> params) {
        AppProperties.SmsTemplate template = templates.get(type);
        if (template == null) {
            return Optional.empty();
        }
        Map<String, String> variables = new LinkedHashMap<>();
        Matcher matcher = PLACEHOLDER.matcher(template.text());
        StringBuilder text = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = params.get(name);
            if (value == null) {
                return Optional.empty();
            }
            String clean = sanitize(value);
            variables.put(name, clean);
            matcher.appendReplacement(text, Matcher.quoteReplacement(clean));
        }
        matcher.appendTail(text);
        return Optional.of(new RenderedSms(blankToNull(template.dltTemplateId()), text.toString(),
                Collections.unmodifiableMap(variables)));
    }

    private String sanitize(String value) {
        String flat = CONTROL.matcher(value).replaceAll(" ").trim();
        return flat.length() > maxVariableLength ? flat.substring(0, maxVariableLength) : flat;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
