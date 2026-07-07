package com.repoguard.agent.external;

import com.repoguard.agent.common.SensitiveTextSanitizer;
import java.util.Objects;
import java.util.function.UnaryOperator;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientResponseException;

public final class ExternalHttpFailureDetail {

    private static final int MAX_RESPONSE_BODY_LENGTH = 240;
    private static final int MAX_HEADER_VALUE_LENGTH = 64;
    private static final String RATE_LIMIT_REMAINING_HEADER = "X-RateLimit-Remaining";
    private static final String RATE_LIMIT_RESET_HEADER = "X-RateLimit-Reset";

    private final String retryAfter;
    private final String rateLimitRemaining;
    private final String rateLimitReset;
    private final String responseBody;

    private ExternalHttpFailureDetail(
        String retryAfter,
        String rateLimitRemaining,
        String rateLimitReset,
        String responseBody
    ) {
        this.retryAfter = retryAfter;
        this.rateLimitRemaining = rateLimitRemaining;
        this.rateLimitReset = rateLimitReset;
        this.responseBody = responseBody;
    }

    public static ExternalHttpFailureDetail from(RestClientResponseException ex) {
        Objects.requireNonNull(ex, "ex");
        HttpHeaders headers = ex.getResponseHeaders();
        return new ExternalHttpFailureDetail(
            ExternalRetryAfterHint.fromHeaders(headers),
            cleanHeader(headers, RATE_LIMIT_REMAINING_HEADER),
            cleanHeader(headers, RATE_LIMIT_RESET_HEADER),
            normalizeResponseBody(ex.getResponseBodyAsString())
        );
    }

    public String appendTo(String baseMessage) {
        return appendTo(baseMessage, ExternalHttpFailureDetail::sanitizeForExternalCall);
    }

    public String webhookMessage(String prefix, int statusCode) {
        return appendTo(prefix + " status=" + statusCode, SensitiveTextSanitizer::sanitize);
    }

    private String appendTo(String baseMessage, UnaryOperator<String> bodySanitizer) {
        StringBuilder message = new StringBuilder(Objects.requireNonNull(baseMessage, "baseMessage"));
        if (StringUtils.hasText(retryAfter)) {
            message.append(" retryAfter=").append(retryAfter);
        }
        if (StringUtils.hasText(rateLimitRemaining)) {
            message.append(" rateLimitRemaining=").append(rateLimitRemaining);
        }
        if (StringUtils.hasText(rateLimitReset)) {
            message.append(" rateLimitReset=").append(rateLimitReset);
        }
        String safeBody = safeResponseBody(bodySanitizer);
        if (StringUtils.hasText(safeBody)) {
            message.append(" responseBody=").append(safeBody);
        }
        return message.toString();
    }

    private String safeResponseBody(UnaryOperator<String> bodySanitizer) {
        if (!StringUtils.hasText(responseBody)) {
            return null;
        }
        String safeBody = Objects.requireNonNull(bodySanitizer, "bodySanitizer").apply(responseBody);
        return summarizeResponseBody(safeBody);
    }

    private static String normalizeResponseBody(String body) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        return body.replaceAll("\\s+", " ").trim();
    }

    private static String summarizeResponseBody(String body) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        return body.length() > MAX_RESPONSE_BODY_LENGTH
            ? body.substring(0, MAX_RESPONSE_BODY_LENGTH - 3) + "..."
            : body;
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
            .replaceAll("[^A-Za-z0-9,: GMT+-]", "")
            .replaceAll("\\s+", " ")
            .trim();
        return safe.length() > MAX_HEADER_VALUE_LENGTH ? safe.substring(0, MAX_HEADER_VALUE_LENGTH) : safe;
    }

    private static String sanitizeForExternalCall(String body) {
        return body
            .replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+\\-/=]+", "$1***")
            .replaceAll("(?i)(api[_-]?key\\s*[:=]\\s*[\"']?)[^\"'\\s,}<>]+", "$1***")
            .replaceAll("(?i)([\"']?(?:api[_-]?key|token|secret)[\"']?\\s*[:=]\\s*[\"']?)(?!bearer\\s)[^\"'\\s,}<>]+", "$1***")
            .replaceAll("(?i)(token\\s*[:=]\\s*[\"']?)[^\"'\\s,}<>]+", "$1***")
            .replaceAll("(?i)(secret\\s*[:=]\\s*[\"']?)[^\"'\\s,}<>]+", "$1***")
            .replaceAll("sk-[A-Za-z0-9_-]{8,}", "sk-***")
            .trim();
    }
}
