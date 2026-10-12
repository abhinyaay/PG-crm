package com.pgms.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pgms.config.AppProperties;
import com.pgms.support.TestData;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.transaction.PlatformTransactionManager;

class NotificationDispatcherTest {

    /** 22:00 IST. */
    private static final Instant NIGHT = Instant.parse("2026-10-13T16:30:00Z");

    private final MutableClock clock = new MutableClock(NIGHT);
    private final List<Notification> rows = new ArrayList<>();
    private NotificationRepository repository;
    private AppProperties properties;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationRepository.class);
        when(repository.claimDueBatch(any(), anyInt())).thenAnswer(inv -> rows.stream().filter(Notification::isPending)
                .filter(n -> !n.getNextAttemptAt().isAfter(inv.getArgument(0))).toList());
        when(repository.findById(any())).thenAnswer(inv -> rows.stream().filter(n -> n.getId().equals(inv.getArgument(0))).findFirst());
        properties = mock(AppProperties.class, Answers.RETURNS_DEEP_STUBS);
        when(properties.notifications().dispatchLease()).thenReturn(Duration.ofMinutes(5));
        when(properties.notifications().dispatchBatchSize()).thenReturn(50);
        when(properties.notifications().maxAttempts()).thenReturn(3);
    }

    @Test
    void outcomesAreRecordedPerRow() {
        Notification sent = row(1L, NotificationChannel.EMAIL, NotificationType.PASSWORD_RESET_OTP, false);
        Notification retried = row(2L, NotificationChannel.SMS, NotificationType.PAYMENT_DUE, false);
        Notification urgentRetried = row(3L, NotificationChannel.SMS, NotificationType.PAYMENT_DUE, true);
        Notification rejected = row(4L, NotificationChannel.EMAIL, NotificationType.PAYMENT_DUE, false);
        Notification noProvider = row(5L, NotificationChannel.PUSH, NotificationType.PAYMENT_DUE, false);

        int attempted = dispatcher(
                sender(NotificationChannel.EMAIL, n -> {
                    if (n.getId() == 4L) {
                        throw DeliveryException.permanent("550 no such user");
                    }
                }),
                sender(NotificationChannel.SMS, n -> {
                    throw new IllegalStateException("gateway down");
                })).dispatchBatch();

        assertThat(attempted).isEqualTo(5);
        assertThat(sent.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(sent.getBody()).isEqualTo("[redacted]"); // one-time code removed once delivered
        assertThat(sent.getParams()).isEmpty();
        assertThat(retried.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(retried.getAttempts()).isEqualTo(1);
        // backoff would land at 22:01 IST: a non-urgent SMS waits for 08:00 IST
        assertThat(retried.getNextAttemptAt()).isEqualTo(Instant.parse("2026-10-14T02:30:00Z"));
        assertThat(urgentRetried.getNextAttemptAt()).isEqualTo(NIGHT.plus(Duration.ofMinutes(1)));
        assertThat(rejected.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(rejected.getLastError()).contains("550");
        assertThat(noProvider.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(noProvider.getLastError()).contains("No provider");
    }

    @Test
    void retriesStopAfterMaxAttemptsAndSensitiveContentIsRedacted() {
        Notification otp = row(1L, NotificationChannel.EMAIL, NotificationType.PORTAL_ACCOUNT_CREATED, true);
        NotificationDispatcher dispatcher = dispatcher(sender(NotificationChannel.EMAIL, n -> {
            throw DeliveryException.retryable("timeout", null);
        }));
        for (int i = 0; i < 3; i++) {
            dispatcher.dispatchBatch();
            clock.advance(Duration.ofMinutes(10));
        }
        assertThat(otp.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(otp.getAttempts()).isEqualTo(3);
        assertThat(otp.getBody()).isEqualTo("[redacted]");
        assertThat(dispatcher.dispatchBatch()).isZero();
    }

    @Test
    void stopsSendingWhenTheLeaseIsAboutToExpire() {
        Notification first = row(1L, NotificationChannel.EMAIL, NotificationType.PAYMENT_DUE, false);
        Notification second = row(2L, NotificationChannel.EMAIL, NotificationType.PAYMENT_DUE, false);
        Duration slowSend = Duration.ofMinutes(4).plusSeconds(1);
        int attempted = dispatcher(sender(NotificationChannel.EMAIL, n -> clock.advance(slowSend))).dispatchBatch();

        assertThat(attempted).isEqualTo(1);
        assertThat(first.getStatus()).isEqualTo(NotificationStatus.SENT);
        // still leased: picked up again once the lease runs out
        assertThat(second.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(second.getAttempts()).isZero();
        assertThat(second.getNextAttemptAt()).isEqualTo(NIGHT.plus(Duration.ofMinutes(5)));
    }

    @Test
    void rowsSettledElsewhereAreLeftAlone() {
        Notification row = row(1L, NotificationChannel.EMAIL, NotificationType.PAYMENT_DUE, false);
        when(repository.findById(1L)).thenReturn(Optional.empty());
        dispatcher(sender(NotificationChannel.EMAIL, n -> { })).dispatchDue();
        assertThat(row.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(row.getAttempts()).isZero();
    }

    private Notification row(Long id, NotificationChannel channel, NotificationType type, boolean urgent) {
        NotificationMessage message = NotificationMessage.of(type, "title", "body", null, null).withParam("code", "123456");
        Notification n = TestData.withId(new Notification(1L, null, "x@y.test", channel, message, NIGHT, NIGHT, urgent), id);
        rows.add(n);
        return n;
    }

    private NotificationDispatcher dispatcher(NotificationSender... senders) {
        return new NotificationDispatcher(repository, List.of(senders), properties, new NotificationPolicy(),
                new QuietHours(true, LocalTime.of(21, 0), LocalTime.of(8, 0), ZoneId.of("Asia/Kolkata")),
                mock(PlatformTransactionManager.class), clock);
    }

    private static NotificationSender sender(NotificationChannel channel, Consumer<Notification> behaviour) {
        return new NotificationSender() {
            @Override
            public NotificationChannel channel() {
                return channel;
            }

            @Override
            public void send(Notification notification) {
                behaviour.accept(notification);
            }
        };
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
