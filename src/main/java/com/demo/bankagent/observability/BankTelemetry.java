package com.demo.bankagent.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The application's OpenTelemetry handle. With OBSERVABILITY_ENABLED=false it wraps the no-op implementation,
 * so instrumentation code runs unchanged and creates no spans and no network traffic. Tests replace it with an
 * in-memory exporter. It is deliberately not registered as GlobalOpenTelemetry.
 */
public final class BankTelemetry {

    public static final String INSTRUMENTATION_SCOPE = "com.demo.bankagent";

    private final OpenTelemetry openTelemetry;
    private final Tracer tracer;
    private final boolean exporting;
    private final ExportStats stats;
    private final SdkTracerProvider provider;

    public BankTelemetry(OpenTelemetry openTelemetry, String version, boolean exporting, ExportStats stats,
                         SdkTracerProvider provider) {
        this.openTelemetry = openTelemetry;
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE, version);
        this.exporting = exporting;
        this.stats = stats;
        this.provider = provider;
    }

    public static BankTelemetry noop() {
        return new BankTelemetry(OpenTelemetry.noop(), "0", false, new ExportStats(), null);
    }

    public OpenTelemetry openTelemetry() {
        return openTelemetry;
    }

    public Tracer tracer() {
        return tracer;
    }

    /** True when spans are actually recorded and sent somewhere (OTLP or a test exporter). */
    public boolean exporting() {
        return exporting;
    }

    public ExportStats stats() {
        return stats;
    }

    /** Exports everything still queued (used by tests and on shutdown). */
    public void flush() {
        if (provider != null) {
            provider.forceFlush().join(15, TimeUnit.SECONDS);
        }
    }

    public void shutdown() {
        if (provider != null) {
            provider.shutdown().join(15, TimeUnit.SECONDS);
        }
    }

    /** Export counters for /api/status and the startup self-check. Never holds span content. */
    public static final class ExportStats {
        private final AtomicLong exportedSpans = new AtomicLong();
        private final AtomicLong failedBatches = new AtomicLong();
        private final AtomicReference<String> lastFailure = new AtomicReference<>();
        private final AtomicReference<Instant> lastSuccessAt = new AtomicReference<>();

        public long exportedSpans() {
            return exportedSpans.get();
        }

        public long failedBatches() {
            return failedBatches.get();
        }

        public String lastFailure() {
            return lastFailure.get();
        }

        public Instant lastSuccessAt() {
            return lastSuccessAt.get();
        }

        void success(int spans) {
            exportedSpans.addAndGet(spans);
            lastSuccessAt.set(Instant.now());
        }

        void failure(String reason) {
            failedBatches.incrementAndGet();
            lastFailure.set(reason);
        }
    }

    /** Delegating exporter that records the outcome of every batch. */
    static final class StatsSpanExporter implements SpanExporter {
        private final SpanExporter delegate;
        private final ExportStats stats;

        StatsSpanExporter(SpanExporter delegate, ExportStats stats) {
            this.delegate = delegate;
            this.stats = stats;
        }

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            CompletableResultCode result = delegate.export(spans);
            int count = spans.size();
            result.whenComplete(() -> {
                if (result.isSuccess()) {
                    stats.success(count);
                } else {
                    Throwable failure = result.getFailureThrowable();
                    stats.failure(failure == null ? "export failed (see log)" : failure.getClass().getSimpleName());
                }
            });
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
