package com.pgms.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JacksonConfigTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JacksonConfig().moneyScaleModule());

    @Test
    void moneyIsAlwaysWrittenWithTwoDecimals() throws Exception {
        assertThat(mapper.writeValueAsString(Map.of("a", new BigDecimal("100")))).isEqualTo("{\"a\":100.00}");
        assertThat(mapper.writeValueAsString(Map.of("a", new BigDecimal("100.5")))).isEqualTo("{\"a\":100.50}");
        assertThat(mapper.writeValueAsString(Map.of("a", new BigDecimal("100.00")))).isEqualTo("{\"a\":100.00}");
        // never rounds away precision that is actually there
        assertThat(mapper.writeValueAsString(Map.of("a", new BigDecimal("33.333")))).isEqualTo("{\"a\":33.333}");
        // large values with a negative scale are not written in scientific notation
        assertThat(mapper.writeValueAsString(Map.of("a", new BigDecimal("1E+3")))).isEqualTo("{\"a\":1000.00}");
    }
}
