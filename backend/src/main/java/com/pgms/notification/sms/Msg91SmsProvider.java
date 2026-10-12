package com.pgms.notification.sms;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgms.config.AppProperties;
import com.pgms.notification.DeliveryException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * MSG91 Flow API ({@code POST /api/v5/flow}, header {@code authkey}) - the Indian DLT flow: the request carries the
 * template id and the variable values, never free text. The configured {@code dlt-template-id} is sent as
 * {@code template_id} (the MSG91 template that is linked to the DLT registration); variable names in the
 * configured text must match the variable names of that MSG91 template. MSG91 reports some rejections with
 * HTTP 200 and {@code "type":"error"}; those are treated as permanent failures.
 */
@Component
@ConditionalOnProperty(name = "app.notifications.sms.provider", havingValue = "msg91")
public class Msg91SmsProvider implements SmsProvider {

    private final RestClient client;
    private final AppProperties.Msg91 config;
    private final ObjectMapper json;

    @Autowired
    public Msg91SmsProvider(RestClient.Builder builder, AppProperties properties, ObjectMapper json) {
        this(ProviderHttp.client(builder, properties.notifications().sms().msg91().baseUrl(),
                properties.notifications().sms().connectTimeout(), properties.notifications().sms().readTimeout()),
                properties.notifications().sms().msg91(), json);
    }

    Msg91SmsProvider(RestClient client, AppProperties.Msg91 config, ObjectMapper json) {
        if (config.authKey() == null || config.authKey().isBlank()) {
            throw new IllegalStateException("MSG91 SMS provider needs APP_MSG91_AUTH_KEY");
        }
        this.client = client;
        this.config = config;
        this.json = json;
    }

    @Override
    public String name() {
        return "msg91";
    }

    @Override
    public void send(SmsMessage message) {
        if (message.dltTemplateId() == null) {
            throw DeliveryException.permanent("No DLT template id configured for " + message.type());
        }
        Map<String, Object> recipient = new LinkedHashMap<>();
        recipient.put("mobiles", message.to().substring(1)); // MSG91 expects the country code without '+'
        recipient.putAll(message.variables());
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("template_id", message.dltTemplateId());
        if (config.senderId() != null && !config.senderId().isBlank()) {
            request.put("sender", config.senderId());
        }
        request.put("short_url", "0");
        request.put("recipients", List.of(recipient));
        String response;
        try {
            response = client.post()
                    .uri("/api/v5/flow")
                    .header("authkey", config.authKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            throw ProviderHttp.classify("msg91", e);
        }
        rejectErrorPayload(response);
    }

    private void rejectErrorPayload(String response) {
        if (response == null || response.isBlank()) {
            return;
        }
        try {
            JsonNode node = json.readTree(response);
            if ("error".equalsIgnoreCase(node.path("type").asText())) {
                throw DeliveryException.permanent("msg91 rejected the message: " + ProviderHttp.abbreviate(node.path("message").asText()));
            }
        } catch (JsonProcessingException ignored) {
            // a 2xx with a non-JSON body: accepted as far as we can tell
        }
    }
}
