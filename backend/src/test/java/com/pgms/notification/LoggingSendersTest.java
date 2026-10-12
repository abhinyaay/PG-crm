package com.pgms.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

import com.pgms.config.AppProperties;
import com.pgms.notification.sms.LoggingSmsProvider;
import com.pgms.notification.sms.SmsMessage;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.Mockito;

/** The development providers must never throw (and never need configuration). */
class LoggingSendersTest {

    @Test
    void loggingEmailSenderAcceptsAnyAddress() {
        LoggingEmailSender sender = new LoggingEmailSender();
        assertThat(sender.channel()).isEqualTo(NotificationChannel.EMAIL);
        for (String address : new String[] {"meera@nest.test", "not-an-email", null}) {
            Notification n = new Notification(1L, null, address, NotificationChannel.EMAIL,
                    NotificationMessage.of(NotificationType.PAYMENT_DUE, "Due", "Pay", null, null), Instant.now(), Instant.now(), false);
            assertThatCode(() -> sender.send(n)).doesNotThrowAnyException();
        }
    }

    @Test
    void loggingSmsProviderWithAndWithoutContent() {
        for (boolean logContent : new boolean[] {true, false}) {
            AppProperties properties = Mockito.mock(AppProperties.class, Answers.RETURNS_DEEP_STUBS);
            when(properties.notifications().sms().logContent()).thenReturn(logContent);
            LoggingSmsProvider provider = new LoggingSmsProvider(properties);
            assertThat(provider.name()).isEqualTo("log");
            assertThatCode(() -> provider.send(new SmsMessage(1L, NotificationType.PASSWORD_RESET_OTP, "+919876543210",
                    "123456 is your code", "1107", Map.of("code", "123456")))).doesNotThrowAnyException();
        }
    }
}
