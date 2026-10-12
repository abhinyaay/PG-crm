package com.pgms.notification;

import com.pgms.notification.NotificationDtos.OrganizationSettingItem;
import com.pgms.notification.NotificationDtos.PreferencesUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Organization-wide channel switches, e.g. no SMS for a notification type to control cost. Own organization only. */
@RestController
@RequestMapping("/api/v1/organizations/{id}/notification-settings")
@Tag(name = "Notifications")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','PROPERTY_ADMIN')")
public class OrganizationNotificationSettingsController {

    private final NotificationPreferenceService service;

    public OrganizationNotificationSettingsController(NotificationPreferenceService service) {
        this.service = service;
    }

    @GetMapping
    public List<OrganizationSettingItem> get(@PathVariable Long id) {
        return service.organizationSettings(id);
    }

    @PutMapping
    @Operation(summary = "Switch type/channel cells on or off for the whole organization (mandatory cells are 422)")
    public List<OrganizationSettingItem> update(@PathVariable Long id, @Valid @RequestBody PreferencesUpdateRequest request) {
        return service.updateOrganizationSettings(id, request.items());
    }
}
