package com.repoguard.agent.external;

import com.repoguard.agent.common.SensitiveTextSanitizer;
import java.util.Objects;
import java.util.function.UnaryOperator;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientResponseException;

public final class ExternalHttpFailureDetail {

    private final String retryAfter;
    private final String responseBody;

    private ExternalHttpFailureDetail(String retryAfter, String responseBody) {
        this.retryAfter = retryAfter;
        this.responseBody = responseBody;
    }

    public static ExternalHttpFailureDetail from(RestClientResponseException ex) {
        Objects.requireNonNull(ex, "ex");
        return new ExternalHttpFailureDetail(
            ExternalRetryAfterHint.fromHeaders(ex.getResponseHeaders()),
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
        return Objects.requireNonNull(bodySanitizer, "bodySanitizer").apply(responseBody);
    }

    private static String normalizeResponseBody(String body) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        return body.replaceAll("\\s+", " ").trim();
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
