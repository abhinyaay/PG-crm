package com.pgms.notification.sms;

import com.pgms.common.util.PhoneNumbers;
import com.pgms.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development stand-in: logs the SMS instead of sending it. The text (which may hold a one-time code) is only logged
 * when {@code app.notifications.sms.log-content=true}.
 */
@Component
@ConditionalOnProperty(name = "app.notifications.sms.provider", havingValue = "log", matchIfMissing = true)
public class LoggingSmsProvider implements SmsProvider {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsProvider.class);

    private final boolean logContent;

    public LoggingSmsProvider(AppProperties properties) {
        this.logContent = properties.notifications().sms().logContent();
    }

    @Override
    public String name() {
        return "log";
    }

    @Override
    public void send(SmsMessage message) {
        if (logContent) {
            log.info("[sms-stub] to={} type={} dltTemplateId={} text=\"{}\"", PhoneNumbers.mask(message.to()), message.type(),
                    message.dltTemplateId(), message.text());
        } else {
            log.info("[sms-stub] to={} type={} dltTemplateId={} length={}", PhoneNumbers.mask(message.to()), message.type(),
                    message.dltTemplateId(), message.text().length());
        }
    }
}
