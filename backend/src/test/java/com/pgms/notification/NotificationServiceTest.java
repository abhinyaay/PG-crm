package com.pgms.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pgms.config.AppProperties;
import com.pgms.notification.sms.SmsTemplates;
import com.pgms.organization.Organization;
import com.pgms.organization.OrganizationRepository;
import com.pgms.security.CurrentUserProvider;
import com.pgms.support.TestData;
import com.pgms.tenant.Tenant;
import com.pgms.tenant.TenantRepository;
import com.pgms.user.RoleName;
import com.pgms.user.User;
import com.pgms.user.UserRepository;
import com.pgms.user.UserStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

/** Recipient resolution, preference/setting application and quiet hours at the moment outbox rows are created. */
class NotificationServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** 22:30 IST: inside quiet hours. */
    private static final Instant NIGHT = Instant.parse("2026-10-13T17:00:00Z");
    private static final Instant NEXT_MORNING = Instant.parse("2026-10-14T02:30:00Z");

    private final List<Notification> saved = new ArrayList<>();
    private NotificationRepository repository;
    private UserRepository users;
    private TenantRepository tenants;
    private NotificationPreferenceRepository preferences;
    private OrganizationNotificationSettingRepository settings;
    private NotificationService service;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationRepository.class);
        when(repository.save(any())).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        users = mock(UserRepository.class);
        tenants = mock(TenantRepository.class);
        preferences = mock(NotificationPreferenceRepository.class);
        settings = mock(OrganizationNotificationSettingRepository.class);
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        when(organizations.findById(3L)).thenReturn(Optional.of(new Organization("Green Nest", "green-nest", null, null)));
        AppProperties properties = mock(AppProperties.class, Answers.RETURNS_DEEP_STUBS);
        when(properties.notifications().sms().defaultCountryCode()).thenReturn("91");
        when(properties.notifications().email().brandName()).thenReturn("PG Manager");
        SmsTemplates templates = new SmsTemplates(Map.of(
                "BOOKING_CONFIRMED", new AppProperties.SmsTemplate("1107", "{pg}: Bed reserved, ref {ref}."),
                "PASSWORD_RESET_OTP", new AppProperties.SmsTemplate("1108", "{code} is your {pg} code.")), 30);
        service = new NotificationService(repository, users, tenants, organizations, preferences, settings,
                new NotificationPolicy(), templates, new QuietHours(true, LocalTime.of(21, 0), LocalTime.of(8, 0), IST),
                mock(CurrentUserProvider.class), properties, Clock.fixed(NIGHT, IST));
        tenant = TestData.withId(new Tenant(3L, 1L, "Meera Iyer", "meera@nest.test", "98765 43210"), 11L);
        when(tenants.findById(11L)).thenReturn(Optional.of(tenant));
    }

    @Test
    void residentWithoutLoginGetsEmailNowAndSmsAfterQuietHours() {
        service.notifyTenant(11L, booking());

        assertThat(saved).extracting(Notification::getChannel).containsExactly(NotificationChannel.EMAIL, NotificationChannel.SMS);
        Notification email = saved.get(0);
        assertThat(email.getRecipientAddress()).isEqualTo("meera@nest.test");
        assertThat(email.getNextAttemptAt()).isEqualTo(NIGHT);
        assertThat(email.getParams()).containsEntry("pg", "Green Nest").containsEntry("name", "Meera").containsEntry("ref", "B-1");
        Notification sms = saved.get(1);
        assertThat(sms.getRecipientAddress()).isEqualTo("+919876543210");
        assertThat(sms.getBody()).isEqualTo("Green Nest: Bed reserved, ref B-1.");
        assertThat(sms.getNextAttemptAt()).isEqualTo(NEXT_MORNING);
        assertThat(sms.isUrgent()).isFalse();
    }

    @Test
    void urgentSmsIgnoresQuietHours() {
        service.notifyTenant(11L, booking().urgent(true));
        assertThat(saved.get(1).getNextAttemptAt()).isEqualTo(NIGHT);
        assertThat(saved.get(1).isUrgent()).isTrue();
    }

    @Test
    void organizationSwitchAndSuppressionSkipChannels() {
        when(settings.findByOrganizationIdAndType(3L, NotificationType.BOOKING_CONFIRMED)).thenReturn(List.of(
                new OrganizationNotificationSetting(new OrganizationNotificationSetting.Key(3L, NotificationType.BOOKING_CONFIRMED,
                        NotificationChannel.SMS), false, NIGHT, 1L)));
        service.notifyTenant(11L, booking());
        assertThat(saved).extracting(Notification::getChannel).containsExactly(NotificationChannel.EMAIL);

        saved.clear();
        when(settings.findByOrganizationIdAndType(3L, NotificationType.BOOKING_CONFIRMED)).thenReturn(List.of());
        TestData.with(tenant, "smsOptOutAt", NIGHT);
        TestData.with(tenant, "emailBouncedAt", NIGHT);
        service.notifyTenant(11L, booking());
        assertThat(saved).isEmpty();
    }

    @Test
    void residentLoginUsesItsPreferencesAndInbox() {
        User login = TestData.withId(new User(3L, TestData.role(RoleName.TENANT), "meera@nest.test", "x", "Meera Iyer", null), 21L);
        TestData.with(tenant, "userId", 21L);
        when(users.findById(21L)).thenReturn(Optional.of(login));
        when(preferences.findByUserIdAndNotificationType(21L, NotificationType.BOOKING_CONFIRMED)).thenReturn(List.of(
                new NotificationPreference(21L, NotificationType.BOOKING_CONFIRMED, NotificationChannel.EMAIL, false, NIGHT)));

        service.notifyTenant(11L, booking());

        assertThat(saved).extracting(Notification::getChannel).containsExactly(NotificationChannel.IN_APP, NotificationChannel.SMS);
        assertThat(saved.get(0).getRecipientUserId()).isEqualTo(21L);
        assertThat(saved.get(0).getParams()).isEmpty(); // in-app rows carry no template variables
    }

    @Test
    void userWithoutPhoneFallsBackToTheTenantPhoneAndRestrictionIsHonoured() {
        User login = TestData.withId(new User(3L, TestData.role(RoleName.TENANT), "meera@nest.test", "x", "Meera Iyer", null), 21L);
        when(users.findById(21L)).thenReturn(Optional.of(login));
        when(tenants.findByUserId(21L)).thenReturn(Optional.of(tenant));

        service.notifyUser(21L, NotificationMessage.of(NotificationType.PASSWORD_RESET_OTP, "Code", "Use it", "USER", 21L)
                .withParam("code", "123456").onlyVia(NotificationChannel.SMS));

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getChannel()).isEqualTo(NotificationChannel.SMS);
        assertThat(saved.get(0).getRecipientAddress()).isEqualTo("+919876543210");
        assertThat(saved.get(0).getNextAttemptAt()).isEqualTo(NIGHT); // reset codes are urgent by type
    }

    @Test
    void missingTemplateVariableSkipsOnlyTheSms() {
        service.notifyTenant(11L, NotificationMessage.of(NotificationType.BOOKING_CONFIRMED, "t", "b", "BOOKING", 1L));
        assertThat(saved).extracting(Notification::getChannel).containsExactly(NotificationChannel.EMAIL);
    }

    @Test
    void inactiveUsersAndDuplicatesAreSkipped() {
        User inactive = TestData.withId(new User(3L, TestData.role(RoleName.MANAGER), "m@nest.test", "x", "M", null), 30L);
        TestData.with(inactive, "status", UserStatus.INACTIVE);
        when(users.findById(30L)).thenReturn(Optional.of(inactive));
        service.notifyUser(30L, booking());
        assertThat(saved).isEmpty();

        when(repository.existsByDedupKeyAndChannel(any(), any())).thenReturn(true);
        service.notifyTenant(11L, booking().withDedupKey("K"));
        assertThat(saved).isEmpty();
        verify(preferences, never()).findByUserIdAndNotificationType(anyLong(), any());
    }

    private static NotificationMessage booking() {
        return NotificationMessage.of(NotificationType.BOOKING_CONFIRMED, "Bed reserved - B-1", "Held until tomorrow.", "BOOKING", 1L)
                .withParam("ref", "B-1");
    }
}
