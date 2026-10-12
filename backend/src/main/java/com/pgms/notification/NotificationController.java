package com.pgms.notification;

import com.pgms.common.api.PageResponse;
import com.pgms.notification.NotificationDtos.PreferenceItem;
import com.pgms.notification.NotificationDtos.PreferencesUpdateRequest;
import com.pgms.notification.NotificationService.NotificationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The caller's in-app inbox and notification preferences. Every authenticated role has both. */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications")
public class NotificationController {

    private final NotificationService service;
    private final NotificationPreferenceService preferences;

    public NotificationController(NotificationService service, NotificationPreferenceService preferences) {
        this.service = service;
        this.preferences = preferences;
    }

    @GetMapping
    public PageResponse<NotificationResponse> inbox(@RequestParam(defaultValue = "false") boolean unreadOnly, Pageable pageable) {
        return service.inbox(unreadOnly, pageable);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount() {
        return Map.of("unread", service.unreadCount());
    }

    @PostMapping("/{id}/read")
    public NotificationResponse markRead(@PathVariable Long id) {
        return service.markRead(id);
    }

    @PostMapping("/read-all")
    public Map<String, Integer> markAllRead() {
        return Map.of("updated", service.markAllRead());
    }

    @GetMapping("/preferences")
    @Operation(summary = "Channel preferences per notification type for the caller's role (mandatory ones are locked)")
    public List<PreferenceItem> preferences() {
        return preferences.myPreferences();
    }

    @PutMapping("/preferences")
    @Operation(summary = "Change channel preferences; switching off a mandatory channel is 422 PREFERENCE_MANDATORY")
    public List<PreferenceItem> updatePreferences(@Valid @RequestBody PreferencesUpdateRequest request) {
        return preferences.updateMyPreferences(request.items());
    }
}
