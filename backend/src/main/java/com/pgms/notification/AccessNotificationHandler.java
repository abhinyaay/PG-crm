package com.pgms.notification;

import com.pgms.access.AccessEvents;
import com.pgms.config.AppProperties;
import com.pgms.property.Property;
import com.pgms.property.PropertyRepository;
import com.pgms.user.RoleName;
import com.pgms.user.User;
import com.pgms.user.UserRepository;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Notifications of the biometric access module (kept apart from {@link NotificationEventHandler} so the module stays
 * self-contained). Recipients are the property's managers; when a property has no manager assigned, the
 * organization's property admins are alerted instead so the alert is never lost.
 */
@Component
public class AccessNotificationHandler {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd MMM HH:mm");

    private final NotificationService notifications;
    private final UserRepository userRepository;
    private final PropertyRepository propertyRepository;
    private final ZoneId zone;

    public AccessNotificationHandler(NotificationService notifications, UserRepository userRepository,
                                     PropertyRepository propertyRepository, AppProperties properties) {
        this.notifications = notifications;
        this.userRepository = userRepository;
        this.propertyRepository = propertyRepository;
        this.zone = properties.businessTimezone();
    }

    @EventListener
    public void on(AccessEvents.EntryLimitExceeded e) {
        String time = DATE_TIME.format(e.occurredAt().atZone(zone));
        String property = propertyName(e.propertyId());
        String name = e.tenantName() == null ? "Resident #" + e.tenantId() : e.tenantName();
        notifications.notifyUsers(managers(e.organizationId(), e.propertyId()), NotificationMessage.of(
                NotificationType.ACCESS_LIMIT_EXCEEDED, "Entry limit reached: " + name,
                name + " tried to enter " + property + " at " + time + " but has already used " + e.entriesToday() + " of "
                        + e.limit() + " entries allowed today.", "ACCESS_EVENT", e.eventId())
                .withDedupKey("ACCESS_LIMIT:" + e.eventId())
                .withParam("resident", name).withParam("time", time).withParam("limit", e.limit()));
        notifications.notifyTenant(e.tenantId(), NotificationMessage.of(NotificationType.ACCESS_LIMIT_EXCEEDED,
                "Daily entry limit reached",
                "You have used all " + e.limit() + " entries allowed today at " + property
                        + ". The property manager has been informed.", "ACCESS_EVENT", e.eventId())
                .withDedupKey("ACCESS_LIMIT:" + e.eventId()));
    }

    @EventListener
    public void on(AccessEvents.DeviceOffline e) {
        String since = e.lastSeenAt() == null ? "unknown" : DATE_TIME.format(e.lastSeenAt().atZone(zone));
        long outage = e.lastSeenAt() == null ? 0 : e.lastSeenAt().getEpochSecond();
        notifications.notifyUsers(managers(e.organizationId(), e.propertyId()), NotificationMessage.of(
                NotificationType.ACCESS_DEVICE_OFFLINE, "Gate device offline: " + e.deviceName(),
                "The biometric device \"" + e.deviceName() + "\" (serial " + e.serialNumber() + ") at "
                        + propertyName(e.propertyId()) + " has not contacted the server since " + since
                        + " (more than " + e.offlineMinutes() + " minutes). Check its power and network connection.",
                "ACCESS_DEVICE", e.deviceId())
                .withDedupKey("ACCESS_DEVICE_OFFLINE:" + e.deviceId() + ":" + outage)
                .withParam("device", e.deviceName()).withParam("time", since));
    }

    private List<User> managers(Long organizationId, Long propertyId) {
        List<User> managers = userRepository.findActiveAssignedToProperty(propertyId, Set.of(RoleName.MANAGER));
        return managers.isEmpty()
                ? userRepository.findActiveByOrganizationAndRoles(organizationId, Set.of(RoleName.PROPERTY_ADMIN))
                : managers;
    }

    private String propertyName(Long propertyId) {
        return propertyRepository.findById(propertyId).map(Property::getName).orElse("property #" + propertyId);
    }
}
