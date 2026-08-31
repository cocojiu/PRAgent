package com.repoguard.agent.observability;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.http.HttpHeaders;

/**
 * Thread-bound W3C propagation bridge for the current HTTP/MQ transition.
 * It intentionally exposes no payload, token, prompt, or source-code fields.
 */
public final class TracePropagation {

    public static final String TRACEPARENT_HEADER = "traceparent";
    public static final String TRACESTATE_HEADER = "tracestate";
    public static final String MDC_SPAN_ID = "spanId";
    public static final String MDC_TRACEPARENT = TRACEPARENT_HEADER;
    private static final ThreadLocal<W3CTraceContext> CURRENT = new ThreadLocal<>();

    private TracePropagation() {
    }

    public static Optional<W3CTraceContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static String currentTraceId() {
        return current().map(W3CTraceContext::traceId).orElse(null);
    }

    /** Starts a child server/consumer span from a trusted W3C parent, or a new root. */
    public static Scope openIncoming(String incomingTraceparent) {
        W3CTraceContext context = W3CTraceContext.parse(incomingTraceparent)
            .map(W3CTraceContext::child)
            .orElseGet(W3CTraceContext::root);
        return withContext(context);
    }

    public static Scope withContext(W3CTraceContext context) {
        return new Scope(Objects.requireNonNull(context, "context"));
    }

    public static void inject(HttpHeaders headers) {
        Objects.requireNonNull(headers, "headers");
        current().ifPresent(context -> headers.set(TRACEPARENT_HEADER, context.traceparent()));
    }

    public static void inject(MessageProperties properties) {
        Objects.requireNonNull(properties, "properties");
        current().ifPresent(context -> properties.setHeader(TRACEPARENT_HEADER, context.traceparent()));
    }

    /**
     * Filter for future span instrumentation. Only low-cardinality operation
     * metadata is allowed; credentials, prompts, diffs, and source code are not.
     */
    public static Map<String, String> safeAttributes(Map<String, ?> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        Set<String> allowed = Set.of("component", "operation", "outcome", "provider");
        return attributes.entrySet().stream()
            .filter(entry -> allowed.contains(entry.getKey()))
            .filter(entry -> entry.getValue() != null)
            .collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey,
                entry -> String.valueOf(entry.getValue()).substring(0, Math.min(128, String.valueOf(entry.getValue()).length()))
            ));
    }

    public static final class Scope implements AutoCloseable {

        private final W3CTraceContext previousContext;
        private final String previousTraceId;
        private final String previousSpanId;
        private final String previousTraceparent;
        private boolean closed;

        private Scope(W3CTraceContext context) {
            previousContext = CURRENT.get();
            previousTraceId = MDC.get(LogContext.TRACE_ID);
            previousSpanId = MDC.get(MDC_SPAN_ID);
            previousTraceparent = MDC.get(MDC_TRACEPARENT);
            CURRENT.set(context);
            MDC.put(LogContext.TRACE_ID, context.traceId());
            MDC.put(MDC_SPAN_ID, context.spanId());
            MDC.put(MDC_TRACEPARENT, context.traceparent());
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previousContext == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previousContext);
            }
            restoreMdc(LogContext.TRACE_ID, previousTraceId);
            restoreMdc(MDC_SPAN_ID, previousSpanId);
            restoreMdc(MDC_TRACEPARENT, previousTraceparent);
        }

        private static void restoreMdc(String key, String value) {
            if (value == null || value.isBlank()) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        }
    }
}
