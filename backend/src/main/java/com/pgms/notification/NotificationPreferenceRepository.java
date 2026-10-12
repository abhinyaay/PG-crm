package com.pgms.notification;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, Long> {

    List<NotificationPreference> findByUserId(Long userId);

    List<NotificationPreference> findByUserIdAndNotificationType(Long userId, NotificationType type);
}
