package com.pgms.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.validation.ValidationConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean Validation's temporal constraints ({@code @FutureOrPresent}, {@code @Past} ...) default to the JVM clock and
 * time zone. Containers usually run in UTC while business dates live in the business time zone, so "today" would be
 * judged by the wrong calendar for several hours a day. Validation uses the same injectable clock as the services.
 */
@Configuration
public class ValidationConfig {

    @Bean
    public ValidationConfigurationCustomizer businessClockForValidation(Clock clock) {
        return configuration -> configuration.clockProvider(() -> clock);
    }
}
