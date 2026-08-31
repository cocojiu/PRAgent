package com.repoguard.agent.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String TRACE_PARENT_HEADER = TracePropagation.TRACEPARENT_HEADER;
    public static final String TRACE_ID_ATTRIBUTE = "repoguard.traceId";
    public static final String TRACE_PARENT_ATTRIBUTE = "repoguard.traceparent";
    public static final String MDC_TRACE_ID = "traceId";

    private static final int MAX_TRACE_ID_LENGTH = 64;

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        W3CTraceContext context = W3CTraceContext.parse(request.getHeader(TRACE_PARENT_HEADER))
            .map(W3CTraceContext::child)
            .orElseGet(W3CTraceContext::root);
        String traceId = resolveTraceId(request.getHeader(TRACE_ID_HEADER), context.traceId());
        String previousTraceId = MDC.get(MDC_TRACE_ID);
        try (TracePropagation.Scope _ = TracePropagation.withContext(context)) {
            request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
            request.setAttribute(TRACE_PARENT_ATTRIBUTE, context.traceparent());
            response.setHeader(TRACE_ID_HEADER, traceId);
            response.setHeader(TRACE_PARENT_HEADER, context.traceparent());
            MDC.put(MDC_TRACE_ID, traceId);
            try {
                filterChain.doFilter(request, response);
            } finally {
                restoreTraceId(previousTraceId);
            }
        }
    }

    private String resolveTraceId(String candidate, String fallback) {
        if (!StringUtils.hasText(candidate)) {
            return fallback;
        }
        String sanitized = candidate.trim().replaceAll("[^A-Za-z0-9._:-]", "");
        if (!StringUtils.hasText(sanitized)) {
            return fallback;
        }
        return sanitized.length() > MAX_TRACE_ID_LENGTH ? sanitized.substring(0, MAX_TRACE_ID_LENGTH) : sanitized;
    }

    private void restoreTraceId(String previousTraceId) {
        if (previousTraceId == null || previousTraceId.isBlank()) {
            MDC.remove(MDC_TRACE_ID);
        } else {
            MDC.put(MDC_TRACE_ID, previousTraceId);
        }
    }
}
