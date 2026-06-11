package com.repoguard.agent.external;

import java.net.SocketTimeoutException;
import java.util.Locale;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

public final class ExternalCallErrorClassifier {

    private ExternalCallErrorClassifier() {
    }

    public static ExternalCallException github(RuntimeException ex) {
        if (ex instanceof ExternalCallException externalCallException) {
            return externalCallException;
        }
        Integer statusCode = statusCode(ex);
        String detail = detail(ex);
        if (statusCode != null) {
            return switch (statusCode) {
                case 401 -> failure("GitHub", "github_token_invalid", false, statusCode, detail, ex);
                case 403 -> failure("GitHub", "github_permission_denied", false, statusCode, detail, ex);
                case 404 -> failure("GitHub", "github_target_not_found", false, statusCode, detail, ex);
                case 422 -> failure("GitHub", "github_request_invalid", false, statusCode, detail, ex);
                case 429 -> failure("GitHub", "github_rate_limited", true, statusCode, detail, ex);
                default -> {
                    if (statusCode >= 500) {
                        yield failure("GitHub", "github_service_unavailable", true, statusCode, detail, ex);
                    }
                    yield failure("GitHub", "github_request_failed", false, statusCode, detail, ex);
                }
            };
        }
        if (isTimeout(ex, detail)) {
            return failure("GitHub", "github_timeout", true, null, detail, ex);
        }
        return failure("GitHub", "github_request_failed", false, null, detail, ex);
    }

    public static ExternalCallException llm(RuntimeException ex) {
        if (ex instanceof ExternalCallException externalCallException) {
            return externalCallException;
        }
        Integer statusCode = statusCode(ex);
        String detail = detail(ex);
        if (statusCode != null) {
            return switch (statusCode) {
                case 401, 403 -> failure("LLM", "llm_auth_failed", false, statusCode, detail, ex);
                case 404 -> failure("LLM", "llm_model_or_endpoint_not_found", false, statusCode, detail, ex);
                case 408, 429 -> failure("LLM", "llm_rate_limited", true, statusCode, detail, ex);
                case 400, 422 -> failure("LLM", "llm_request_invalid", false, statusCode, detail, ex);
                default -> {
                    if (statusCode >= 500) {
                        yield failure("LLM", "llm_service_unavailable", true, statusCode, detail, ex);
                    }
                    yield failure("LLM", "llm_request_failed", false, statusCode, detail, ex);
                }
            };
        }
        if (isTimeout(ex, detail)) {
            return failure("LLM", "llm_timeout", true, null, detail, ex);
        }
        return failure("LLM", "llm_request_failed", false, null, detail, ex);
    }

    private static ExternalCallException failure(
        String system,
        String category,
        boolean retryable,
        Integer statusCode,
        String detail,
        RuntimeException cause
    ) {
        return new ExternalCallException(system, category, retryable, statusCode, truncate(detail), cause);
    }

    private static Integer statusCode(RuntimeException ex) {
        if (ex instanceof RestClientResponseException responseException) {
            return responseException.getStatusCode().value();
        }
        return null;
    }

    private static boolean isTimeout(RuntimeException ex, String detail) {
        if (ex instanceof ResourceAccessException && containsTimeout(detail)) {
            return true;
        }
        Throwable cause = ex.getCause();
        while (cause != null) {
            if (cause instanceof SocketTimeoutException) {
                return true;
            }
            cause = cause.getCause();
        }
        return containsTimeout(detail);
    }

    private static boolean containsTimeout(String detail) {
        return StringUtils.hasText(detail)
            && detail.toLowerCase(Locale.ROOT).matches(".*(timeout|timed out|read timed out|connect timed out).*");
    }

    private static String detail(RuntimeException ex) {
        String message = ex.getMessage();
        if (!StringUtils.hasText(message)) {
            return ex.getClass().getSimpleName();
        }
        return message.replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 300 ? value.substring(0, 297) + "..." : value;
    }
}
