package com.pgms.notification.sms;

import com.pgms.config.AppProperties;
import com.pgms.notification.DeliveryException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Twilio Programmable Messaging: {@code POST /2010-04-01/Accounts/{sid}/Messages.json} (form encoded) with HTTP basic
 * auth (account SID + auth token). Sends the rendered template text; Indian DLT registration for Twilio senders is
 * done in the Twilio console. Uses a messaging service when configured, otherwise the from-number.
 */
@Component
@ConditionalOnProperty(name = "app.notifications.sms.provider", havingValue = "twilio")
public class TwilioSmsProvider implements SmsProvider {

    private final RestClient client;
    private final AppProperties.Twilio config;

    @Autowired
    public TwilioSmsProvider(RestClient.Builder builder, AppProperties properties) {
        this(ProviderHttp.client(builder, properties.notifications().sms().twilio().baseUrl(),
                properties.notifications().sms().connectTimeout(), properties.notifications().sms().readTimeout()),
                properties.notifications().sms().twilio());
    }

    TwilioSmsProvider(RestClient client, AppProperties.Twilio config) {
        if (isBlank(config.accountSid()) || isBlank(config.authToken())
                || (isBlank(config.fromNumber()) && isBlank(config.messagingServiceSid()))) {
            throw new IllegalStateException("Twilio SMS provider needs APP_TWILIO_ACCOUNT_SID, APP_TWILIO_AUTH_TOKEN and "
                    + "APP_TWILIO_FROM_NUMBER or APP_TWILIO_MESSAGING_SERVICE_SID");
        }
        this.client = client;
        this.config = config;
    }

    @Override
    public String name() {
        return "twilio";
    }

    @Override
    public void send(SmsMessage message) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", message.to());
        form.add("Body", message.text());
        if (!isBlank(config.messagingServiceSid())) {
            form.add("MessagingServiceSid", config.messagingServiceSid());
        } else {
            form.add("From", config.fromNumber());
        }
        try {
            client.post()
                    .uri("/2010-04-01/Accounts/{sid}/Messages.json", config.accountSid())
                    .headers(h -> h.setBasicAuth(config.accountSid(), config.authToken()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw ProviderHttp.classify("twilio", e);
        } catch (IllegalArgumentException e) {
            throw DeliveryException.permanent("twilio request could not be built: " + e.getMessage());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
