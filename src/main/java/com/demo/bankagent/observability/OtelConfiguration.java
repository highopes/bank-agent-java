package com.demo.bankagent.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanLimits;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the OpenTelemetry SDK when OBSERVABILITY_ENABLED=true: official OTLP/HTTP protobuf exporter, endpoint and
 * auth header from {@link AoSettings}, project/stream routing by NAME both as request headers ("project",
 * "logstream") and as Resource attributes (o11y: splunk_ao.*; galileo: galileo.*), exactly as the official
 * splunk-ao-python / galileo SDK exporters do. Export is asynchronous (batch processor): an export failure is
 * logged and counted but never changes a business result.
 */
@Configuration(proxyBeanMethods = false)
public class OtelConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OtelConfiguration.class);

    @Bean(destroyMethod = "shutdown")
    public BankTelemetry bankTelemetry(AoSettings settings, ObservabilityProperties tuning,
                                       @Value("${bank-agent.observability.service-version:dev}") String version) {
        if (!settings.observabilityEnabled()) {
            log.info("OpenTelemetry: OBSERVABILITY_ENABLED=false - no spans are recorded or exported.");
            return BankTelemetry.noop();
        }
        String project = settings.projectName().value();
        String stream = settings.agentStream().value();
        OtlpHttpSpanExporter otlp = OtlpHttpSpanExporter.builder()
                .setEndpoint(settings.otlpEndpoint().value())
                .setTimeout(tuning.exportTimeout())
                .addHeader(settings.otlpAuthHeader().value(), settings.otlpAuthValue().value())
                .addHeader("project", project)
                .addHeader("logstream", stream)
                .build();
        BankTelemetry.ExportStats stats = new BankTelemetry.ExportStats();
        Resource resource = Resource.getDefault().merge(Resource.create(Attributes.builder()
                .put("service.name", settings.serviceName().value())
                .put("service.version", version)
                .put("deployment.environment.name", tuning.deploymentEnvironment())
                .put(settings.projectRoutingAttribute(), project)
                .put(settings.streamRoutingAttribute(), stream)
                .build()));
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(tuning.samplingRatio())))
                .setSpanLimits(SpanLimits.builder().setMaxAttributeValueLength(tuning.maxAttributeLength()).build())
                .addSpanProcessor(BatchSpanProcessor.builder(new BankTelemetry.StatsSpanExporter(otlp, stats))
                        .setScheduleDelay(tuning.batchScheduleDelay())
                        .setMaxExportBatchSize(tuning.maxExportBatchSize())
                        .setMaxQueueSize(tuning.maxQueueSize())
                        .setExporterTimeout(tuning.exportTimeout())
                        .build())
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        log.info("OpenTelemetry: exporting spans via OTLP/HTTP to {} (profile {}, project '{}', stream '{}', "
                        + "service.name={}, sampling={}, trace content captured={})",
                AoSettings.host(settings.otlpEndpoint().value()), settings.profile().key(), project, stream,
                settings.serviceName().value(), tuning.samplingRatio(), settings.demoTraceContent());
        return new BankTelemetry(sdk, version, true, stats, provider);
    }

    /** Exposed so that Spring Boot's own OpenTelemetry auto-configuration backs off; closed via BankTelemetry. */
    @Bean(destroyMethod = "")
    public OpenTelemetry openTelemetry(BankTelemetry telemetry) {
        return telemetry.openTelemetry();
    }
}
