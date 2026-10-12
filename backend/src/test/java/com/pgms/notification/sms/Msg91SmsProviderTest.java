package com.pgms.notification.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgms.config.AppProperties;
import com.pgms.notification.DeliveryException;
import com.pgms.notification.NotificationType;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class Msg91SmsProviderTest {

    private static final String URL = "https://control.msg91.test/api/v5/flow";

    private MockRestServiceServer server;
    private Msg91SmsProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://control.msg91.test");
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new Msg91SmsProvider(builder.build(), new AppProperties.Msg91("https://control.msg91.test", "auth-123", "PGMSIN"),
                new ObjectMapper());
    }

    @Test
    void sendsTheDltTemplateIdAndVariablesNeverFreeText() {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("code", "123456");
        variables.put("pg", "Green Nest");
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("authkey", "auth-123"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.template_id").value("1107161234567890123"))
                .andExpect(jsonPath("$.sender").value("PGMSIN"))
                .andExpect(jsonPath("$.recipients[0].mobiles").value("919876543210"))
                .andExpect(jsonPath("$.recipients[0].code").value("123456"))
                .andExpect(jsonPath("$.recipients[0].pg").value("Green Nest"))
                .andExpect(jsonPath("$.text").doesNotExist())
                .andRespond(withSuccess("{\"type\":\"success\",\"message\":\"3a6b\"}", MediaType.APPLICATION_JSON));

        provider.send(new SmsMessage(5L, NotificationType.PASSWORD_RESET_OTP, "+919876543210",
                "123456 is your Green Nest password reset code.", "1107161234567890123", variables));

        server.verify();
        assertThat(provider.name()).isEqualTo("msg91");
    }

    @Test
    void errorPayloadWithHttp200IsAPermanentFailure() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"type\":\"error\",\"message\":\"Invalid template\"}",
                MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.send(message("1107")))
                .isInstanceOfSatisfying(DeliveryException.class, e -> {
                    assertThat(e.isPermanent()).isTrue();
                    assertThat(e.getMessage()).contains("Invalid template");
                });
    }

    @Test
    void httpErrorsAreClassified() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"type\":\"error\"}"));
        assertThatThrownBy(() -> provider.send(message("1107")))
                .isInstanceOfSatisfying(DeliveryException.class, e -> assertThat(e.isPermanent()).isTrue());
        server.reset();
        server.expect(requestTo(URL)).andRespond(withServerError());
        assertThatThrownBy(() -> provider.send(message("1107")))
                .isInstanceOfSatisfying(DeliveryException.class, e -> assertThat(e.isPermanent()).isFalse());
        server.reset();
        server.expect(requestTo(URL)).andRespond(withException(new IOException("connection reset")));
        assertThatThrownBy(() -> provider.send(message("1107")))
                .isInstanceOfSatisfying(DeliveryException.class, e -> assertThat(e.isPermanent()).isFalse());
    }

    @Test
    void nonJsonSuccessBodyIsAccepted() {
        server.expect(requestTo(URL)).andRespond(withSuccess("OK", MediaType.TEXT_PLAIN));
        provider.send(message("1107"));
        server.verify();
    }

    @Test
    void missingTemplateIdOrAuthKey() {
        assertThatThrownBy(() -> provider.send(message(null)))
                .isInstanceOfSatisfying(DeliveryException.class, e -> assertThat(e.isPermanent()).isTrue());
        assertThatThrownBy(() -> new Msg91SmsProvider(RestClient.create(), new AppProperties.Msg91("https://x", " ", null),
                new ObjectMapper())).isInstanceOf(IllegalStateException.class);
    }

    private static SmsMessage message(String templateId) {
        return new SmsMessage(1L, NotificationType.PAYMENT_DUE, "+919876543210", "text", templateId, Map.of("amt", "1"));
    }
}
