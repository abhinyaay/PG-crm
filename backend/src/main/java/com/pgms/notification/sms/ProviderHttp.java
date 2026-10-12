package com.pgms.notification.sms;

import com.pgms.notification.DeliveryException;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** HTTP plumbing shared by the SMS gateways: timeouts and the permanent-vs-retryable error rule. */
final class ProviderHttp {

    private ProviderHttp() {
    }

    static RestClient client(RestClient.Builder builder, String baseUrl, Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    /**
     * 4xx (except 408 and 429) means the provider rejected this message - retrying cannot help, so the row fails at
     * once. 5xx, 408/429, timeouts and connection errors are transient and retried with backoff.
     */
    static DeliveryException classify(String provider, RestClientException e) {
        if (e instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            String detail = provider + " responded " + status + ": " + abbreviate(response.getResponseBodyAsString());
            boolean transientStatus = status == HttpStatus.REQUEST_TIMEOUT.value()
                    || status == HttpStatus.TOO_MANY_REQUESTS.value() || status >= 500;
            return transientStatus ? DeliveryException.retryable(detail, e) : new DeliveryException(detail, true, e);
        }
        if (e instanceof ResourceAccessException) {
            return DeliveryException.retryable(provider + " unreachable or timed out: " + e.getMessage(), e);
        }
        return DeliveryException.retryable(provider + " call failed: " + e.getMessage(), e);
    }

    static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() > 200 ? flat.substring(0, 200) + "..." : flat;
    }
}
