package com.pgms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ValidatorFactory;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The validator used for request bodies must judge dates by the application's business clock. */
class ValidationClockIT extends AbstractIntegrationTest {

    @Autowired
    private ValidatorFactory validatorFactory;

    @Autowired
    private Clock clock;

    @Test
    void requestValidationUsesTheBusinessClock() {
        assertThat(validatorFactory.getClockProvider().getClock()).isSameAs(clock);
    }
}
