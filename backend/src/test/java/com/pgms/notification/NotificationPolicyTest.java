package com.pgms.notification;

import static com.pgms.notification.NotificationChannel.EMAIL;
import static com.pgms.notification.NotificationChannel.IN_APP;
import static com.pgms.notification.NotificationChannel.SMS;
import static org.assertj.core.api.Assertions.assertThat;

import com.pgms.notification.NotificationPolicy.TypeChannel;
import com.pgms.user.RoleName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NotificationPolicyTest {

    private final NotificationPolicy policy = new NotificationPolicy();

    /** Matrix defaults for "no explicit org setting, no user preference". */
    @ParameterizedTest(name = "{0} to {1} via {2}: important={3} -> {4}")
    @CsvSource({
        "BOOKING_CONFIRMED,      RESIDENT, SMS,    false, true",
        "MOVE_IN,                STAFF,    EMAIL,  false, false",
        "MOVE_IN,                STAFF,    SMS,    false, false",
        "ROOM_TRANSFER,          RESIDENT, SMS,    false, false",
        "INVOICE_ISSUED,         RESIDENT, SMS,    false, false",
        "INVOICE_ISSUED,         RESIDENT, SMS,    true,  true",
        "PAYMENT_RECEIVED,       RESIDENT, IN_APP, false, true",
        "MAINTENANCE_ASSIGNED,   STAFF,    SMS,    false, false",
        "MAINTENANCE_ASSIGNED,   STAFF,    SMS,    true,  true",
        "MAINTENANCE_UPDATE,     RESIDENT, EMAIL,  false, false",
        "MAINTENANCE_UPDATE,     RESIDENT, EMAIL,  true,  true",
        "MAINTENANCE_SLA_BREACH, STAFF,    SMS,    false, false",
        "COMPLAINT_UPDATE,       STAFF,    EMAIL,  true,  false",
        "COMPLAINT_ESCALATED,    STAFF,    SMS,    true,  true",
        "PASSWORD_RESET_OTP,     RESIDENT, IN_APP, false, false",
        "PASSWORD_CHANGED,       STAFF,    EMAIL,  false, true",
        "PASSWORD_CHANGED,       STAFF,    SMS,    false, false"
    })
    void matrixDefaults(NotificationType type, Audience audience, NotificationChannel channel, boolean important,
                        boolean expected) {
        assertThat(policy.shouldSend(type, audience, channel, important, null, null)).isEqualTo(expected);
    }

    @Test
    void mandatoryBeatsOrganizationAndUserOptOut() {
        assertThat(policy.shouldSend(NotificationType.PAYMENT_RECEIVED, Audience.RESIDENT, IN_APP, false, false, false)).isTrue();
        assertThat(policy.shouldSend(NotificationType.PASSWORD_RESET_OTP, Audience.STAFF, EMAIL, false, false, false)).isTrue();
    }

    @Test
    void organizationSwitchBeatsUserPreference() {
        assertThat(policy.shouldSend(NotificationType.BOOKING_CONFIRMED, Audience.RESIDENT, SMS, false, false, true)).isFalse();
        assertThat(policy.shouldSend(NotificationType.BOOKING_CONFIRMED, Audience.RESIDENT, SMS, false, true, true)).isTrue();
    }

    @Test
    void userPreferenceBeatsMatrixDefaultBothWays() {
        assertThat(policy.shouldSend(NotificationType.ROOM_TRANSFER, Audience.RESIDENT, SMS, false, null, true)).isTrue();
        assertThat(policy.shouldSend(NotificationType.BOOKING_CONFIRMED, Audience.RESIDENT, EMAIL, false, null, false)).isFalse();
    }

    @Test
    void channelsThatAreNotOfferedCannotBeEnabledByPreference() {
        assertThat(policy.shouldSend(NotificationType.MOVE_IN, Audience.STAFF, SMS, true, true, true)).isFalse();
        assertThat(policy.shouldSend(NotificationType.MOVE_IN, Audience.RESIDENT, NotificationChannel.PUSH, true, true, true))
                .isFalse();
    }

    @Test
    void unknownAudienceCombinationFallsBackToInAppOnly() {
        assertThat(policy.rule(NotificationType.MAINTENANCE_CREATED, Audience.RESIDENT)).isEqualTo(NotificationPolicy.FALLBACK);
        assertThat(policy.shouldSend(NotificationType.MAINTENANCE_CREATED, Audience.RESIDENT, IN_APP, false, null, null)).isTrue();
        assertThat(policy.shouldSend(NotificationType.MAINTENANCE_CREATED, Audience.RESIDENT, EMAIL, false, null, true)).isFalse();
    }

    @Test
    void offeredCellsDependOnTheAudience() {
        assertThat(policy.offered(Audience.RESIDENT))
                .contains(new TypeChannel(NotificationType.BOOKING_CONFIRMED, SMS),
                        new TypeChannel(NotificationType.PAYMENT_RECEIVED, IN_APP))
                .doesNotContain(new TypeChannel(NotificationType.MAINTENANCE_CREATED, IN_APP),
                        new TypeChannel(NotificationType.PASSWORD_RESET_OTP, IN_APP));
        assertThat(policy.offered(Audience.STAFF))
                .contains(new TypeChannel(NotificationType.MAINTENANCE_CREATED, EMAIL))
                .doesNotContain(new TypeChannel(NotificationType.MAINTENANCE_CREATED, SMS));
        assertThat(policy.offeredToAnyAudience()).contains(new TypeChannel(NotificationType.MAINTENANCE_CREATED, IN_APP),
                new TypeChannel(NotificationType.BOOKING_CONFIRMED, SMS));
    }

    @Test
    void flagsAndAudienceMapping() {
        assertThat(policy.isMandatoryForAnyAudience(NotificationType.PAYMENT_RECEIVED, IN_APP)).isTrue();
        assertThat(policy.isMandatoryForAnyAudience(NotificationType.PAYMENT_RECEIVED, EMAIL)).isFalse();
        assertThat(policy.defaultEnabled(NotificationType.ROOM_TRANSFER, Audience.RESIDENT, SMS)).isFalse();
        assertThat(policy.defaultEnabled(NotificationType.PAYMENT_RECEIVED, Audience.RESIDENT, IN_APP)).isTrue();
        assertThat(policy.isUrgent(NotificationType.PASSWORD_RESET_OTP)).isTrue();
        assertThat(policy.isUrgent(NotificationType.PAYMENT_DUE)).isFalse();
        assertThat(policy.isSensitive(NotificationType.PORTAL_ACCOUNT_CREATED)).isTrue();
        assertThat(policy.isSensitive(NotificationType.PASSWORD_CHANGED)).isFalse();
        assertThat(Audience.of(RoleName.TENANT)).isEqualTo(Audience.RESIDENT);
        assertThat(Audience.of(RoleName.MANAGER)).isEqualTo(Audience.STAFF);
    }
}
