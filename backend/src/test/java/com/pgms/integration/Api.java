package com.pgms.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Thin MockMvc wrapper so tests read like HTTP conversations. MockMvc is safe to use from several threads. */
@Component
public class Api {

    private final MockMvc mvc;
    private final ObjectMapper json;

    public Api(MockMvc mvc, ObjectMapper json) {
        this.mvc = mvc;
        this.json = json;
    }

    public record Response(int status, JsonNode body, MvcResult raw) {
        public long id() {
            return body.path("id").asLong();
        }
    }

    public String login(String email) {
        Response r = post("/api/v1/auth/login", null, "{\"email\":\"" + email + "\",\"password\":\"" + Fixtures.PASSWORD + "\"}");
        if (r.status() != 200) {
            throw new IllegalStateException("login failed for " + email + ": " + r.body());
        }
        return r.body().get("accessToken").asText();
    }

    public Response get(String path, String token) {
        return perform(MockMvcRequestBuilders.get(path), token, null);
    }

    public Response post(String path, String token, String body, String... headers) {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post(path);
        for (int i = 0; i + 1 < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return perform(request, token, body);
    }

    public Response put(String path, String token, String body) {
        return perform(MockMvcRequestBuilders.put(path), token, body);
    }

    public Response patch(String path, String token, String body) {
        return perform(MockMvcRequestBuilders.patch(path), token, body);
    }

    public Response delete(String path, String token) {
        return perform(MockMvcRequestBuilders.delete(path), token, null);
    }

    private Response perform(MockHttpServletRequestBuilder request, String token, String body) {
        try {
            if (token != null) {
                request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }
            if (body != null) {
                request.contentType(MediaType.APPLICATION_JSON).content(body);
            }
            MvcResult result = mvc.perform(request).andReturn();
            String content = result.getResponse().getContentAsString();
            JsonNode node = content.isBlank() || !content.trim().startsWith("{") && !content.trim().startsWith("[")
                    ? json.createObjectNode() : json.readTree(content);
            return new Response(result.getResponse().getStatus(), node, result);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
