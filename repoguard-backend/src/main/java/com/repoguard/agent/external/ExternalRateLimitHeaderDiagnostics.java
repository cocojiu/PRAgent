package com.repoguard.agent.external;

import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;

final class ExternalRateLimitHeaderDiagnostics {

    private static final int MAX_HEADER_VALUE_LENGTH = 64;

    private static final String LIMIT_HEADER = "X-RateLimit-Limit";
    private static final String REMAINING_HEADER = "X-RateLimit-Remaining";
    private static final String USED_HEADER = "X-RateLimit-Used";
    private static final String RESET_HEADER = "X-RateLimit-Reset";
    private static final String RESOURCE_HEADER = "X-RateLimit-Resource";

    private final String limit;
    private final String remaining;
    private final String used;
    private final String reset;
    private final String resource;

    private ExternalRateLimitHeaderDiagnostics(
        String limit,
        String remaining,
        String used,
        String reset,
        String resource
    ) {
        this.limit = limit;
        this.remaining = remaining;
        this.used = used;
        this.reset = reset;
        this.resource = resource;
    }

    static ExternalRateLimitHeaderDiagnostics from(HttpHeaders headers) {
        return new ExternalRateLimitHeaderDiagnostics(
            cleanHeader(headers, LIMIT_HEADER),
            cleanHeader(headers, REMAINING_HEADER),
            cleanHeader(headers, USED_HEADER),
            cleanHeader(headers, RESET_HEADER),
            cleanHeader(headers, RESOURCE_HEADER)
        );
    }

    void appendTo(StringBuilder message) {
        append(message, "rateLimitLimit", limit);
        append(message, "rateLimitRemaining", remaining);
        append(message, "rateLimitUsed", used);
        append(message, "rateLimitReset", reset);
        append(message, "rateLimitResource", resource);
    }

    private static void append(StringBuilder message, String name, String value) {
        if (StringUtils.hasText(value)) {
            message.append(' ').append(name).append('=').append(value);
        }
    }

    private static String cleanHeader(HttpHeaders headers, String name) {
        if (headers == null) {
            return "";
        }
        String value = headers.getFirst(name);
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String safe = value
            .replaceAll("[^A-Za-z0-9,: GMT+_.-]", "")
            .replaceAll("\\s+", " ")
            .trim();
        return safe.length() > MAX_HEADER_VALUE_LENGTH ? safe.substring(0, MAX_HEADER_VALUE_LENGTH) : safe;
    }
}
