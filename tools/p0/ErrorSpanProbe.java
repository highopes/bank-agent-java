// Phase 2 P0 probe (local use only): isolates which error representation on a span makes Splunk AO drop the
// input/output of a trace. Sends one complete synthetic trace per variant in ONE OTLP request (like the official
// SDK) and prints the trace IDs for read-back. Run with tools/p0/run-probe.sh ErrorSpanProbe.
//
// Variants (only the tool span differs):
//   ok          no error information (baseline)
//   status      OTel span status ERROR with a description
//   status-bare OTel span status ERROR without a description
//   errortype   attribute error.type only
//   event       span event "exception" (exception.type / exception.message) only
//   aostatus    attribute splunk_ao.status_code=403 (int) only
//   sdk         what the official SDK emits for a failed step: splunk_ao.status_code=500 + error.type="500"
//   ctl-<action>-<matched>  no error, but a control span under the tool span (as ControlEventReporter emits it),
//               e.g. ctl-observe-true, ctl-deny-false, ctl-deny-true, ctl-steer-true

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class ErrorSpanProbe {

    public static void main(String[] args) {
        String realm = require("AO_REALM");
        String token = require("AO_O11Y_TOKEN");
        String project = System.getProperty("probe.project", "bank-agent-java");
        String stream = System.getProperty("probe.stream", "bank-java-demo");
        String label = System.getProperty("probe.label", "p0-error-span");
        List<String> variants = args.length > 0 ? List.of(args)
                : List.of("ok", "status", "status-bare", "errortype", "event", "aostatus", "sdk");

        OtlpHttpSpanExporter otlp = OtlpHttpSpanExporter.builder()
                .setEndpoint("https://ingest." + realm + ".observability.splunkcloud.com/v2/trace/otlp")
                .setTimeout(15, TimeUnit.SECONDS)
                .addHeader("X-SF-Token", token)
                .addHeader("project", project)
                .addHeader("logstream", stream)
                .build();
        Collector collector = new Collector();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .setResource(Resource.getDefault().merge(Resource.create(Attributes.builder()
                        .put("service.name", "bank-agent-java-p0-probe")
                        .put("splunk_ao.project.name", project)
                        .put("splunk_ao.logstream.name", stream).build())))
                .addSpanProcessor(SimpleSpanProcessor.create(collector))
                .build();
        Tracer tracer = provider.get("bank-agent-java.p0-probe", "0.1.0");

        for (String variant : variants) {
            collector.spans.clear();
            String session = UUID.randomUUID().toString();
            Span root = tracer.spanBuilder("invoke_agent BankingAssistant").setSpanKind(SpanKind.INTERNAL)
                    .setAttribute("gen_ai.operation.name", "invoke_agent")
                    .setAttribute("gen_ai.agent.name", "BankingAssistant")
                    .setAttribute("gen_ai.conversation.id", session)
                    .setAttribute("gen_ai.input.messages", messages("user", label + " " + variant + ": synthetic question"))
                    .startSpan();
            try (Scope ignored = root.makeCurrent()) {
                Span llm = tracer.spanBuilder("chat gpt-5-nano").setSpanKind(SpanKind.CLIENT)
                        .setAttribute("gen_ai.operation.name", "chat")
                        .setAttribute("gen_ai.provider.name", "openai")
                        .setAttribute("gen_ai.request.model", "gpt-5-nano")
                        .setAttribute("gen_ai.conversation.id", session)
                        .setAttribute("gen_ai.input.messages", messages("user", "synthetic prompt"))
                        .setAttribute("gen_ai.output.messages", messages("assistant", "synthetic answer"))
                        .setAttribute("gen_ai.usage.input_tokens", 10L)
                        .setAttribute("gen_ai.usage.output_tokens", 5L)
                        .startSpan();
                llm.end();
                Span tool = tracer.spanBuilder("execute_tool createTransferDraft").setSpanKind(SpanKind.INTERNAL)
                        .setAttribute("gen_ai.operation.name", "execute_tool")
                        .setAttribute("gen_ai.tool.name", "createTransferDraft")
                        .setAttribute("gen_ai.conversation.id", session)
                        .setAttribute("gen_ai.tool.call.arguments", "{\"beneficiary\":\"synthetic\"}")
                        .setAttribute("gen_ai.tool.call.result", "{\"error\":\"synthetic\"}")
                        .startSpan();
                switch (variant) {
                    case "status" -> tool.setStatus(StatusCode.ERROR, "synthetic failure");
                    case "status-bare" -> tool.setStatus(StatusCode.ERROR);
                    case "errortype" -> tool.setAttribute("error.type", "control_denied");
                    case "event" -> tool.addEvent("exception", Attributes.of(
                            AttributeKey.stringKey("exception.type"), "CONTROL_DENIED",
                            AttributeKey.stringKey("exception.message"), "synthetic"));
                    case "aostatus" -> tool.setAttribute("splunk_ao.status_code", 403L);
                    case "sdk" -> {
                        tool.setAttribute("splunk_ao.status_code", 500L);
                        tool.setAttribute("error.type", "500");
                        tool.setStatus(StatusCode.ERROR);
                    }
                    default -> { }
                }
                if (variant.startsWith("ctl-")) {
                    String[] parts = variant.split("-");
                    try (Scope inTool = tool.makeCurrent()) {
                        Span control = tracer.spanBuilder("p0-error-span-control").setSpanKind(SpanKind.INTERNAL)
                                .setAttribute("galileo.span.kind", "control")
                                .setAttribute("gen_ai.operation.name", "control")
                                .setAttribute("agent_control.control_id", 20L)
                                .setAttribute("agent_control.control_name", "p0-error-span-control")
                                .setAttribute("agent_control.agent_name", "bank-agent-java")
                                .setAttribute("agent_control.check_stage", "pre")
                                .setAttribute("agent_control.applies_to", "tool_call")
                                .setAttribute("agent_control.evaluator_name", "regex")
                                .setAttribute("agent_control.selector_path", "input")
                                .setAttribute("agent_control.action", parts[1])
                                .setAttribute("agent_control.matched", Boolean.parseBoolean(parts[2]))
                                .setAttribute("agent_control.confidence", 1.0)
                                .setAttribute("gen_ai.conversation.id", session)
                                .startSpan();
                        control.end();
                    }
                }
                tool.end();
            }
            root.setAttribute("gen_ai.output.messages", messages("assistant", "synthetic final answer"));
            root.end();
            String traceId = root.getSpanContext().getTraceId();
            CompletableResultCode result = otlp.export(new ArrayList<>(collector.spans));
            result.join(20, TimeUnit.SECONDS);
            System.out.println(variant + " trace=" + traceId + " spans=" + collector.spans.size()
                    + " export=" + (result.isSuccess() ? "OK" : "FAILED"));
        }
        provider.shutdown().join(10, TimeUnit.SECONDS);
        otlp.shutdown().join(10, TimeUnit.SECONDS);
    }

    private static String messages(String role, String text) {
        return "[{\"role\":\"" + role + "\",\"parts\":[{\"type\":\"text\",\"content\":\"" + text + "\"}]}]";
    }

    private static String require(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            System.err.println(name + " is not set");
            System.exit(2);
        }
        return value;
    }

    /** Collects ended spans so that each trace is exported in a single request. */
    private static final class Collector implements SpanExporter {
        final List<SpanData> spans = new ArrayList<>();

        public CompletableResultCode export(Collection<SpanData> batch) {
            spans.addAll(batch);
            return CompletableResultCode.ofSuccess();
        }

        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
