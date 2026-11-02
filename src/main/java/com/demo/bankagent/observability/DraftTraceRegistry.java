package com.demo.bankagent.observability;

import io.opentelemetry.api.trace.SpanContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Remembers which span created a transfer draft, so that the later, independent "payment.confirm" HTTP request can
 * carry a span LINK to it (it is a different trace, not a fake child). Bounded, in memory only.
 */
@Component
public class DraftTraceRegistry {

    private static final int MAX_ENTRIES = 1000;

    private final Map<String, SpanContext> byDraft = new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SpanContext> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    public synchronized void remember(String draftId, SpanContext context) {
        if (draftId != null && context != null && context.isValid()) {
            byDraft.put(draftId, context);
        }
    }

    public synchronized Optional<SpanContext> creationSpan(String draftId) {
        return Optional.ofNullable(byDraft.get(draftId));
    }

    public synchronized void clear() {
        byDraft.clear();
    }
}
