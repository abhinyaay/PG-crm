package com.pgms.notification.sms;

import com.pgms.notification.NotificationType;
import java.util.Map;

/** A rendered SMS ready for a provider: E.164 recipient, final text, and the DLT template id with its variables. */
public record SmsMessage(Long notificationId, NotificationType type, String to, String text, String dltTemplateId,
                         Map<String, String> variables) {
}
