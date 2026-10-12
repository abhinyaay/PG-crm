package com.pgms.notification.sms;

import com.pgms.common.util.PhoneNumbers;
import com.pgms.config.AppProperties;
import com.pgms.notification.DeliveryException;
import com.pgms.notification.Notification;
import com.pgms.notification.NotificationChannel;
import com.pgms.notification.NotificationSender;
import org.springframework.stereotype.Component;

/**
 * The SMS channel of the outbox: normalises the number, renders the fixed template from the stored variables and hands
 * the result to the configured {@link SmsProvider}.
 */
@Component
public class SmsSender implements NotificationSender {

    private final SmsProvider provider;
    private final SmsTemplates templates;
    private final String defaultCountryCode;

    public SmsSender(SmsProvider provider, SmsTemplates templates, AppProperties properties) {
        this.provider = provider;
        this.templates = templates;
        this.defaultCountryCode = properties.notifications().sms().defaultCountryCode();
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.SMS;
    }

    @Override
    public void send(Notification notification) {
        String to = PhoneNumbers.toE164(notification.getRecipientAddress(), defaultCountryCode)
                .orElseThrow(() -> DeliveryException.permanent("Invalid phone number"));
        SmsTemplates.RenderedSms sms = templates.render(notification.getType(), notification.getParams())
                .orElseThrow(() -> DeliveryException.permanent("No SMS template (or a variable is missing) for "
                        + notification.getType()));
        provider.send(new SmsMessage(notification.getId(), notification.getType(), to, sms.text(), sms.dltTemplateId(),
                sms.variables()));
    }
}
