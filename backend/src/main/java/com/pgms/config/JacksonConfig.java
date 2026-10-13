package com.pgms.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Every {@link BigDecimal} in the API is money (NUMERIC(12,2)) or a 2-dp rate. Values read back from the database carry
 * scale 2 while values echoed from a request keep the client's scale, so the same payment serialised as {@code 100} when
 * fresh and {@code 100.00} when replayed. Normalising to at least two decimals on output makes the JSON stable;
 * values with more precision are written unchanged (never silently rounded).
 */
@Configuration
public class JacksonConfig {

    /** Registered by Spring Boot's Jackson auto-configuration like every other {@link Module} bean. */
    @Bean
    public Module moneyScaleModule() {
        SimpleModule module = new SimpleModule("pgms-money");
        module.addSerializer(BigDecimal.class, new MoneySerializer());
        return module;
    }

    static final class MoneySerializer extends StdSerializer<BigDecimal> {

        MoneySerializer() {
            super(BigDecimal.class);
        }

        @Override
        public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            gen.writeNumber(normalise(value));
        }

        static BigDecimal normalise(BigDecimal value) {
            return value.scale() < 2 ? value.setScale(2, RoundingMode.UNNECESSARY) : value;
        }
    }
}
