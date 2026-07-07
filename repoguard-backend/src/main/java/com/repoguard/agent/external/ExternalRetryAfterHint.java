package com.repoguard.agent.external;

import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;

public final class ExternalRetryAfterHint {

    private ExternalRetryAfterHint() {
    }

    public static String suggestionSuffix(String detail) {
        String retryAfter = retryAfter(detail);
        if (!StringUtils.hasText(retryAfter)) {
            return "";
        }
        return "建议等待 " + retryAfter + " 后再重试。";
    }

    public static String fromHeaders(HttpHeaders headers) {
        if (headers == null) {
            return "";
        }
        return clean(headers.getFirst(HttpHeaders.RETRY_AFTER));
    }

    static String retryAfter(String detail) {
        return clean(ExternalFailureSignals.retryAfterFromDetail(detail));
    }

    private static String clean(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String safe = value
            .replaceAll("[^A-Za-z0-9,: GMT+-]", "")
            .replaceAll("\\s+", " ")
            .trim();
        return safe.length() > 64 ? safe.substring(0, 64) : safe;
    }
}
