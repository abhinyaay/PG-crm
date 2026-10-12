package com.pgms.notification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class NotificationDtos {

    private NotificationDtos() {
    }

    /**
     * One cell of the caller's preference grid. {@code mandatory} cells cannot be switched off; {@code orgDisabled}
     * means the organization has switched the cell off, so nothing is sent whatever {@code enabled} says.
     */
    public record PreferenceItem(NotificationType type, NotificationChannel channel, boolean enabled, boolean mandatory,
                                 boolean orgDisabled) {
    }

    public record PreferenceChange(@NotNull NotificationType type, @NotNull NotificationChannel channel, @NotNull Boolean enabled) {
    }

    public record PreferencesUpdateRequest(@NotNull @Size(min = 1, max = 200) List<@Valid @NotNull PreferenceChange> items) {
    }

    /** One cell of an organization's channel switches; {@code enabled} defaults to true when never set. */
    public record OrganizationSettingItem(NotificationType type, NotificationChannel channel, boolean enabled, boolean mandatory) {
    }
}
