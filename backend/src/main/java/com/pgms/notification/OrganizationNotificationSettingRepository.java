package com.pgms.notification;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrganizationNotificationSettingRepository
        extends JpaRepository<OrganizationNotificationSetting, OrganizationNotificationSetting.Key> {

    @Query("select s from OrganizationNotificationSetting s where s.id.organizationId = :organizationId")
    List<OrganizationNotificationSetting> findByOrganizationId(@Param("organizationId") Long organizationId);

    @Query("""
            select s from OrganizationNotificationSetting s
            where s.id.organizationId = :organizationId and s.id.notificationType = :type
            """)
    List<OrganizationNotificationSetting> findByOrganizationIdAndType(@Param("organizationId") Long organizationId,
                                                                     @Param("type") NotificationType type);
}
