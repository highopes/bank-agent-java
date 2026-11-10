package com.demo.bankagent.support;

import com.google.protobuf.InvalidProtocolBufferException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * Loopback OTLP/HTTP receiver for the black-box test: records every export request exactly as the OpenTelemetry
 * Java agent sent it (path, headers, protobuf body) and decodes the spans. Answers 200 with an empty body.
 */
public final class OtlpReceiver implements AutoCloseable {

    public record Export(String method, String path, Map<String, String> headers, ExportTraceServiceRequest body) {
    }

    /** One decoded span with its resource and scope. Attribute values are String, Long, Double or Boolean. */
    public record ReceivedSpan(String traceId, String spanId, String parentSpanId, String name, String kind,
                               String scope, Map<String, Object> attributes, List<Map<String, Object>> events,
                               Map<String, Object> resource) {

        public Object attr(String key) {
            return attributes.get(key);
        }

        public boolean isRoot() {
            return parentSpanId.isEmpty();
        }
    }

    private final HttpServer server;
    private final List<Export> exports = new CopyOnWriteArrayList<>();

    public OtlpReceiver() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Export> exports() {
        return List.copyOf(exports);
    }

    public List<ReceivedSpan> spans() {
        List<ReceivedSpan> spans = new ArrayList<>();
        for (Export export : exports) {
            if (export.body() == null) {
                continue;
            }
            for (ResourceSpans rs : export.body().getResourceSpansList()) {
                Map<String, Object> resource = attributes(rs.getResource().getAttributesList());
                for (ScopeSpans ss : rs.getScopeSpansList()) {
                    for (io.opentelemetry.proto.trace.v1.Span s : ss.getSpansList()) {
                        List<Map<String, Object>> events = new ArrayList<>();
                        s.getEventsList().forEach(e -> {
                            Map<String, Object> event = attributes(e.getAttributesList());
                            event.put("event.name", e.getName());
                            events.add(event);
                        });
                        spans.add(new ReceivedSpan(hex(s.getTraceId().toByteArray()), hex(s.getSpanId().toByteArray()),
                                hex(s.getParentSpanId().toByteArray()), s.getName(), s.getKind().name(),
                                ss.getScope().getName(), attributes(s.getAttributesList()), events, resource));
                    }
                }
            }
        }
        return spans;
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] bytes;
        try (InputStream in = exchange.getRequestBody()) {
            bytes = in.readAllBytes();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.isEmpty() ? "" : v.get(0)));
        ExportTraceServiceRequest body;
        try {
            body = ExportTraceServiceRequest.parseFrom(bytes);
        } catch (InvalidProtocolBufferException e) {
            body = null;
        }
        exports.add(new Export(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers, body));
        exchange.getResponseHeaders().add("Content-Type", "application/x-protobuf");
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    private static Map<String, Object> attributes(List<KeyValue> list) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (KeyValue kv : list) {
            map.put(kv.getKey(), value(kv.getValue()));
        }
        return map;
    }

    private static Object value(AnyValue v) {
        return switch (v.getValueCase()) {
            case STRING_VALUE -> v.getStringValue();
            case INT_VALUE -> v.getIntValue();
            case DOUBLE_VALUE -> v.getDoubleValue();
            case BOOL_VALUE -> v.getBoolValue();
            case ARRAY_VALUE -> v.getArrayValue().getValuesList().stream().map(OtlpReceiver::value).toList();
            default -> v.toString();
        };
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
