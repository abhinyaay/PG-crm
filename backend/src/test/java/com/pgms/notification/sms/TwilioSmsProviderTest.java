package com.pgms.notification.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.pgms.config.AppProperties;
import com.pgms.notification.DeliveryException;
import com.pgms.notification.NotificationType;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TwilioSmsProviderTest {

    private static final AppProperties.Twilio CONFIG =
            new AppProperties.Twilio("https://api.twilio.test", "AC123", "secret-token", "+15005550006", null);
    private static final SmsMessage MESSAGE = new SmsMessage(1L, NotificationType.PAYMENT_DUE, "+919876543210",
            "Green Nest: Reminder - Rs.9000 due on 05 Nov 2026 for invoice INV-1.", "1107", Map.of("amt", "9000"));

    private MockRestServiceServer server;
    private TwilioSmsProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CONFIG.baseUrl());
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new TwilioSmsProvider(builder.build(), CONFIG);
    }

    @Test
    void postsTheRenderedTextAsAFormWithBasicAuth() {
        String basic = "Basic " + Base64.getEncoder().encodeToString("AC123:secret-token".getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo("https://api.twilio.test/2010-04-01/Accounts/AC123/Messages.json"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", basic))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formDataContains(Map.of("To", "+919876543210", "From", "+15005550006",
                        "Body", MESSAGE.text())))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{\"sid\":\"SM1\"}"));

        provider.send(MESSAGE);

        server.verify();
        assertThat(provider.name()).isEqualTo("twilio");
    }

    @Test
    void usesTheMessagingServiceWhenConfigured() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CONFIG.baseUrl());
        MockRestServiceServer mock = MockRestServiceServer.bindTo(builder).build();
        TwilioSmsProvider viaService = new TwilioSmsProvider(builder.build(),
                new AppProperties.Twilio(CONFIG.baseUrl(), "AC123", "secret-token", null, "MG999"));
        mock.expect(content().formDataContains(Map.of("MessagingServiceSid", "MG999"))).andRespond(withSuccess());
        viaService.send(MESSAGE);
        mock.verify();
    }

    @ParameterizedTest(name = "HTTP {0} -> permanent={1}")
    @CsvSource({"400, true", "401, true", "404, true", "408, false", "429, false", "500, false", "503, false"})
    void classifiesProviderErrors(int status, boolean permanent) {
        server.expect(requestTo("https://api.twilio.test/2010-04-01/Accounts/AC123/Messages.json"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":21211,\"message\":\"Invalid 'To' Phone Number\"}"));
        assertThatThrownBy(() -> provider.send(MESSAGE))
                .isInstanceOfSatisfying(DeliveryException.class, e -> {
                    assertThat(e.isPermanent()).isEqualTo(permanent);
                    assertThat(e.getMessage()).contains("twilio responded " + status);
                });
    }

    @Test
    void timeoutsAreRetried() {
        server.expect(requestTo("https://api.twilio.test/2010-04-01/Accounts/AC123/Messages.json"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));
        assertThatThrownBy(() -> provider.send(MESSAGE))
                .isInstanceOfSatisfying(DeliveryException.class, e -> assertThat(e.isPermanent()).isFalse());
    }

    @Test
    void missingCredentialsFailAtStartup() {
        assertThatThrownBy(() -> new TwilioSmsProvider(RestClient.create(),
                new AppProperties.Twilio("https://api.twilio.test", "AC123", "", "+1500", null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_TWILIO_AUTH_TOKEN");
        assertThatThrownBy(() -> new TwilioSmsProvider(RestClient.create(),
                new AppProperties.Twilio("https://api.twilio.test", "AC123", "t", null, " ")))
                .isInstanceOf(IllegalStateException.class);
    }
}
