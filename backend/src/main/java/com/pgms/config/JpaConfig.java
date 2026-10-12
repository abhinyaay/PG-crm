package com.pgms.config;

import com.pgms.security.CurrentUserProvider;
import java.time.Clock;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware", dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaConfig {

    /** created_by / updated_by come from the authenticated principal; null for system jobs. */
    @Bean
    public AuditorAware<Long> auditorAware(CurrentUserProvider currentUserProvider) {
        return currentUserProvider::currentUserId;
    }

    @Bean
    public DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(clock.instant());
    }
}
