package com.demo.bankagent.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Loopback HTTP server built on the JDK's com.sun.net.httpserver (no extra test dependency). Responses are
 * scripted per "METHOD /path" (query ignored for matching); every request is recorded with headers and body.
 */
public final class MockHttpServer implements AutoCloseable {

    public record Request(String method, String path, String query, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                    return e.getValue().get(0);
                }
            }
            return null;
        }
    }

    public record Response(int status, String body, Map<String, String> headers, long delayMillis) {
        public static Response json(int status, String body) {
            return new Response(status, body, Map.of(), 0);
        }
    }

    private final HttpServer server;
    private final Map<String, Deque<Function<Request, Response>>> once = new ConcurrentHashMap<>();
    private final Map<String, Function<Request, Response>> always = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    public MockHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public MockHttpServer always(String method, String path, int status, String body) {
        always.put(method + " " + path, r -> Response.json(status, body));
        return this;
    }

    public MockHttpServer always(String method, String path, Function<Request, Response> handler) {
        always.put(method + " " + path, handler);
        return this;
    }

    public MockHttpServer once(String method, String path, int status, String body) {
        return once(method, path, r -> Response.json(status, body));
    }

    public MockHttpServer once(String method, String path, Function<Request, Response> handler) {
        once.computeIfAbsent(method + " " + path, k -> new ConcurrentLinkedDeque<>()).add(handler);
        return this;
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public List<Request> requests(String method, String path) {
        List<Request> list = new ArrayList<>();
        for (Request r : requests) {
            if (r.method().equals(method) && r.path().equals(path)) {
                list.add(r);
            }
        }
        return list;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(), Map.copyOf(exchange.getRequestHeaders()), body);
        requests.add(request);
        String key = request.method() + " " + request.path();
        Deque<Function<Request, Response>> queue = once.get(key);
        Function<Request, Response> handler = queue == null ? null : queue.poll();
        if (handler == null) {
            handler = always.get(key);
        }
        Response response = handler == null ? Response.json(404, "{\"detail\":\"not scripted\"}")
                : handler.apply(request);
        if (response.delayMillis() > 0) {
            try {
                Thread.sleep(response.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = response.body() == null ? new byte[0] : response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        response.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        try {
            exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        } catch (IOException ignored) {
            // client gave up (timeout tests)
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
