package com.pgms.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.pgms.booking.BookingDtos.ReserveBedRequest;
import jakarta.validation.Configuration;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ValidationConfigTest {

    /** A date that is in the future by the JVM clock but in the past by the business clock must be rejected. */
    @Test
    void temporalConstraintsUseTheBusinessClock() {
        Clock business = Clock.fixed(Instant.parse("2030-06-01T00:00:00Z"), ZoneId.of("Asia/Kolkata"));
        Configuration<?> configuration = Validation.byDefaultProvider().configure();
        new ValidationConfig().businessClockForValidation(business).customize(configuration);
        Validator validator = configuration.buildValidatorFactory().getValidator();

        Set<ConstraintViolation<ReserveBedRequest>> violations = validator.validate(
                new ReserveBedRequest(1L, 2L, LocalDate.of(2029, 12, 31), null, null));

        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).containsExactly("expectedCheckInDate");
    }
}
