package com.demo.bankagent.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal JSON client for the AO REST API (o11y: https://app.&lt;realm&gt;.../ao/api with X-SF-Token; galileo:
 * https://api... with Galileo-API-Key). Retries network errors, timeouts and 5xx a bounded number of times with
 * backoff. Credentials are only ever put into request headers, never into logs or exception messages.
 */
public class AoApiClient {

    private static final Logger log = LoggerFactory.getLogger(AoApiClient.class);

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String authHeader;
    private final String credential;
    private final Duration timeout;
    private final int maxAttempts;

    public AoApiClient(HttpClient http, ObjectMapper mapper, String baseUrl, String authHeader, String credential,
                       Duration timeout, int maxAttempts) {
        this.http = http;
        this.mapper = mapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.authHeader = authHeader;
        this.credential = credential;
        this.timeout = timeout;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public JsonNode get(String path) {
        return send("GET", path, null);
    }

    public JsonNode post(String path, Object body) {
        return send("POST", path, body);
    }

    private JsonNode send(String method, String path, Object body) {
        String url = baseUrl + (path.startsWith("/") ? path : "/" + path);
        AoApiException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                        .header("Accept", "application/json")
                        .header(authHeader, credential);
                if (body == null) {
                    builder.method(method, HttpRequest.BodyPublishers.noBody());
                } else {
                    builder.header("Content-Type", "application/json")
                            .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
                }
                HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status / 100 == 2) {
                    String text = response.body();
                    return text == null || text.isBlank() ? mapper.createObjectNode() : mapper.readTree(text);
                }
                last = new AoApiException(status, method + " " + pathOnly(path) + " returned HTTP " + status
                        + detail(response.body()));
                if (status < 500) {
                    throw last;
                }
            } catch (HttpTimeoutException e) {
                last = new AoApiException(method + " " + pathOnly(path) + " timed out after " + timeout.toMillis()
                        + " ms", e);
            } catch (IOException e) {
                last = new AoApiException(method + " " + pathOnly(path) + " failed: " + e.getClass().getSimpleName(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AoApiException(method + " " + pathOnly(path) + " interrupted", e);
            }
            if (attempt < maxAttempts) {
                log.warn("AO API {} {} attempt {}/{} failed ({}); retrying", method, pathOnly(path), attempt,
                        maxAttempts, last.getMessage());
                sleep(300L * attempt * attempt);
            }
        }
        throw last;
    }

    private String detail(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode node = mapper.readTree(body);
            JsonNode detail = node.path("detail");
            String text = detail.isTextual() ? detail.asText() : detail.isMissingNode() ? node.path("message").asText("")
                    : detail.toString();
            return text.isBlank() ? "" : ": " + shorten(text);
        } catch (IOException e) {
            return "";
        }
    }

    private static String pathOnly(String path) {
        int q = path.indexOf('?');
        return q < 0 ? path : path.substring(0, q);
    }

    private static String shorten(String text) {
        String one = text.replaceAll("\\s+", " ");
        return one.length() > 200 ? one.substring(0, 200) + "..." : one;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
