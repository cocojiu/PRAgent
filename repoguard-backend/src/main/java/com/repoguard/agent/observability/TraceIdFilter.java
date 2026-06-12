package com.repoguard.agent.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String TRACE_ID_ATTRIBUTE = "repoguard.traceId";
    public static final String MDC_TRACE_ID = "traceId";

    private static final int MAX_TRACE_ID_LENGTH = 64;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String traceId = resolveTraceId(request.getHeader(TRACE_ID_HEADER));
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        MDC.put(MDC_TRACE_ID, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_ID);
        }
    }

    private String resolveTraceId(String candidate) {
        if (!StringUtils.hasText(candidate)) {
            return generateTraceId();
        }
        String sanitized = candidate.trim().replaceAll("[^A-Za-z0-9._:-]", "");
        if (!StringUtils.hasText(sanitized)) {
            return generateTraceId();
        }
        return sanitized.length() > MAX_TRACE_ID_LENGTH ? sanitized.substring(0, MAX_TRACE_ID_LENGTH) : sanitized;
    }

    private String generateTraceId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
