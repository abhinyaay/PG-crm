package com.pgms.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A real HTTP server standing in for the MSG91 Flow API, so integration tests exercise the whole SMS path (outbox →
 * dispatcher → provider → HTTP). Records every request; a status can be forced per mobile number.
 */
public final class SmsGatewayStub {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> forcedStatus = new ConcurrentHashMap<>();

    public record Request(String path, String authKey, JsonNode body) {
        public JsonNode recipient() {
            return body.path("recipients").path(0);
        }
    }

    SmsGatewayStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Mobile as MSG91 receives it (country code, no '+'). */
    public void respondWith(String mobile, int status) {
        forcedStatus.put(mobile, status);
    }

    public List<Request> requestsTo(String mobile) {
        return requests.stream().filter(r -> mobile.equals(r.recipient().path("mobiles").asText())).toList();
    }

    private void handle(HttpExchange exchange) throws IOException {
        JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
        Request request = new Request(exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("authkey"), body);
        requests.add(request);
        int status = forcedStatus.getOrDefault(request.recipient().path("mobiles").asText(), 200);
        String response = status == 200 ? "{\"type\":\"success\",\"message\":\"3a6b\"}" : "{\"type\":\"error\",\"message\":\"forced\"}";
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
