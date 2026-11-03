// Protocol verification probe (local use only): sends one synthetic, non-sensitive trace to the Splunk AO OTLP
// endpoint with the official OpenTelemetry Java SDK and prints the trace/span IDs for read-back.
// Run with tools/protocol-probes/run-probe.sh OtlpProbe. Credentials come from environment variables only.
//
// Wire format follows splunk/splunk-ao-python@f7a20ed converter/attribute_mapping.py (o11y mode):
// agent, llm, tool and control spans become plain OTLP spans with gen_ai.* / agent_control.* attributes.

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

public class OtlpProbe {

    public static void main(String[] args) {
        Logger http = Logger.getLogger("io.opentelemetry.exporter");
        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(Level.FINE);
        http.addHandler(handler);
        http.setLevel(Level.FINE);

        String realm = require("AO_REALM");
        String token = require("AO_O11Y_TOKEN");
        String endpoint = System.getProperty("probe.endpoint",
                "https://ingest." + realm + ".observability.splunkcloud.com/v2/trace/otlp");
        String project = System.getProperty("probe.project", "bank-agent-java");
        String stream = System.getProperty("probe.stream", "bank-java-demo");
        String routing = System.getProperty("probe.routing", "logstream");
        String label = System.getProperty("probe.label", "p0-otlp-probe");
        boolean control = Boolean.parseBoolean(System.getProperty("probe.control", "true"));
        String controlId = System.getProperty("probe.controlId", "");
        // Session grouping variant: "none" | "span" (splunk_ao.session.id on every span) | "resource".
        String sessionMode = System.getProperty("probe.session", "none");

        OtlpHttpSpanExporterBuilder builder = OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint)
                .setTimeout(15, TimeUnit.SECONDS)
                .addHeader("X-SF-Token", token)
                .addHeader("project", project);
        if (routing.contains("logstream")) {
            builder.addHeader("logstream", stream);
        }
        if (routing.contains("agentstream")) {
            builder.addHeader("agentstream", stream);
        }
        SpanExporter exporter = new ReportingExporter(builder.build());

        var resourceAttrs = Attributes.builder()
                .put("service.name", "bank-agent-java-p0-probe")
                .put("splunk_ao.project.name", project);
        if (routing.contains("logstream")) {
            resourceAttrs.put("splunk_ao.logstream.name", stream);
        }
        if (routing.contains("agentstream")) {
            resourceAttrs.put("splunk_ao.agentstream.name", stream);
        }
        String session = System.getProperty("probe.sessionId", UUID.randomUUID().toString());
        if ("resource".equals(sessionMode)) {
            resourceAttrs.put("splunk_ao.session.id", session);
        }
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .setResource(Resource.getDefault().merge(Resource.create(resourceAttrs.build())))
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .addSpanProcessor(new io.opentelemetry.sdk.trace.SpanProcessor() {
                    public void onStart(io.opentelemetry.context.Context ctx, io.opentelemetry.sdk.trace.ReadWriteSpan span) {
                        if ("span".equals(sessionMode)) {
                            span.setAttribute("splunk_ao.session.id", session);
                        }
                    }
                    public boolean isStartRequired() { return true; }
                    public void onEnd(io.opentelemetry.sdk.trace.ReadableSpan span) { }
                    public boolean isEndRequired() { return false; }
                })
                .build();
        Tracer tracer = provider.get("bank-agent-java.p0-probe", "0.1.0");

        Span root = tracer.spanBuilder("invoke_agent BankingAssistant").setSpanKind(SpanKind.INTERNAL)
                .setAllAttributes(Attributes.builder()
                        .put("gen_ai.operation.name", "invoke_agent")
                        .put("gen_ai.agent.name", "BankingAssistant")
                        .put("gen_ai.provider.name", "openai")
                        .put("splunk_ao.agent.type", "supervisor")
                        .put("gen_ai.conversation.id", session)
                        .put("gen_ai.input.messages", messages("user", label + ": what is my demo balance?", null))
                        .put(AttributeKey.stringArrayKey("splunk_ao.tags"), List.of("p0-probe"))
                        .build())
                .startSpan();
        String llmId;
        String toolId;
        String controlSpanId = "-";
        try (Scope ignored = root.makeCurrent()) {
            Span llm = tracer.spanBuilder("chat gpt-5-nano").setSpanKind(SpanKind.CLIENT)
                    .setAllAttributes(Attributes.builder()
                            .put("gen_ai.operation.name", "chat")
                            .put("gen_ai.provider.name", "openai")
                            .put("gen_ai.request.model", "gpt-5-nano")
                            .put("gen_ai.response.model", "gpt-5-nano-2025-08-07")
                            .put("gen_ai.usage.input_tokens", 11L)
                            .put("gen_ai.usage.output_tokens", 7L)
                            .put(AttributeKey.stringArrayKey("gen_ai.response.finish_reasons"), List.of("stop"))
                            .put("gen_ai.conversation.id", session)
                            .put("gen_ai.input.messages", messages("user", "probe prompt (synthetic)", null))
                            .put("gen_ai.output.messages", messages("assistant", "probe answer (synthetic)", "stop"))
                            .build())
                    .startSpan();
            llmId = llm.getSpanContext().getSpanId();
            llm.end();

            Span tool = tracer.spanBuilder("execute_tool getAccountBalance").setSpanKind(SpanKind.INTERNAL)
                    .setAllAttributes(Attributes.builder()
                            .put("gen_ai.operation.name", "execute_tool")
                            .put("gen_ai.tool.name", "getAccountBalance")
                            .put("gen_ai.tool.call.id", "call-p0-1")
                            .put("gen_ai.tool.call.arguments", "{\"accountType\":\"CHECKING\"}")
                            .put("gen_ai.tool.call.result", "{\"value\":\"synthetic\"}")
                            .put("gen_ai.conversation.id", session)
                            .build())
                    .startSpan();
            toolId = tool.getSpanContext().getSpanId();
            if (control) {
                try (Scope t = tool.makeCurrent()) {
                    var ca = Attributes.builder()
                            .put("galileo.span.kind", "control")
                            .put("gen_ai.operation.name", "control")
                            .put("agent_control.control_name", label + "-control")
                            .put("agent_control.agent_name", "bank-agent-java")
                            .put("agent_control.check_stage", "pre")
                            .put("agent_control.applies_to", "tool_call")
                            .put("agent_control.action", "observe")
                            .put("agent_control.matched", true)
                            .put("agent_control.confidence", 1.0)
                            .put("agent_control.evaluator_name", "regex")
                            .put("agent_control.selector_path", "input")
                            .put("gen_ai.conversation.id", session)
                            .put("gen_ai.input.messages", messages("user", "accountType=CHECKING", null))
                            .put("gen_ai.output.messages", messages("assistant",
                                    "{\"action\":\"observe\",\"confidence\":1.0,\"matched\":true}", "unknown"));
                    if (!controlId.isBlank()) {
                        ca.put("agent_control.control_id", Long.parseLong(controlId));
                    }
                    Span c = tracer.spanBuilder(label + "-control").setSpanKind(SpanKind.INTERNAL)
                            .setAllAttributes(ca.build()).startSpan();
                    controlSpanId = c.getSpanContext().getSpanId();
                    c.end();
                }
            }
            tool.end();
            root.setAttribute("gen_ai.output.messages", messages("assistant", "probe final answer (synthetic)", "stop"));
        } finally {
            root.end();
        }
        CompletableResultCode flush = provider.forceFlush().join(20, TimeUnit.SECONDS);
        provider.shutdown().join(20, TimeUnit.SECONDS);
        System.out.println("routing=" + routing + " endpointHost=" + endpoint.replaceAll("^https://([^/]+).*", "$1"));
        System.out.println("traceId=" + root.getSpanContext().getTraceId());
        System.out.println("rootSpanId=" + root.getSpanContext().getSpanId() + " llmSpanId=" + llmId
                + " toolSpanId=" + toolId + " controlSpanId=" + controlSpanId);
        System.out.println("session=" + session + " flushSuccess=" + flush.isSuccess());
    }

    static String messages(String role, String text, String finishReason) {
        String escaped = text.replace("\\", "\\\\").replace("\"", "\\\"");
        return "[{\"role\":\"" + role + "\",\"parts\":[{\"type\":\"text\",\"content\":\"" + escaped + "\"}]"
                + (finishReason == null ? "" : ",\"finish_reason\":\"" + finishReason + "\"") + "}]";
    }

    static String require(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(name + " is not set");
        }
        return v;
    }

    /** Prints the per-batch export result without revealing request headers. */
    static final class ReportingExporter implements SpanExporter {
        private final SpanExporter delegate;

        ReportingExporter(SpanExporter delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            CompletableResultCode result = delegate.export(spans);
            result.join(20, TimeUnit.SECONDS);
            System.out.println("export spans=" + spans.size() + " success=" + result.isSuccess()
                    + " names=" + spans.stream().map(SpanData::getName).toList());
            return result;
        }

        @Override
        public CompletableResultCode flush() {
            return delegate.flush();
        }

        @Override
        public CompletableResultCode shutdown() {
            return delegate.shutdown();
        }
    }
}
