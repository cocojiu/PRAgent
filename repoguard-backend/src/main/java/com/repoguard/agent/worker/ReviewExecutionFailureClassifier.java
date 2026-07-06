package com.repoguard.agent.worker;

import com.repoguard.agent.external.ExternalCallException;
import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

@Component
class ReviewExecutionFailureClassifier {

    String failureCategory(RuntimeException ex) {
        if (ex instanceof ExternalCallException externalCallException) {
            return externalCallException.getCategory();
        }
        if (ex instanceof RestClientResponseException responseException) {
            return httpFailureCategory(responseException.getStatusCode().value());
        }
        if (isStateConflict(ex)) {
            return "review_state_conflict";
        }
        if (ex instanceof DataAccessException) {
            return "review_database_error";
        }
        String detail = normalizedDetail(ex);
        Integer statusCode = statusCodeFromDetail(detail);
        if (statusCode != null) {
            return httpFailureCategory(statusCode);
        }
        if (detail.contains("retryafter=") || detail.contains("retry-after")) {
            return "review_external_rate_limited";
        }
        if (isTimeout(ex, detail)) {
            return "review_timeout";
        }
        if (ex instanceof IllegalArgumentException
            || detail.contains("payload")
            || detail.contains("parse")
            || detail.contains("json")) {
            return "review_payload_invalid";
        }
        if (detail.contains("config")
            || detail.contains("provider")
            || detail.contains("api key")
            || detail.contains("token")
            || detail.contains("credential")
            || detail.contains("required")
            || detail.contains("missing")) {
            return "review_configuration_invalid";
        }
        return "review_execution_failed";
    }

    private String httpFailureCategory(int statusCode) {
        if (statusCode == 429) {
            return "review_external_rate_limited";
        }
        if (statusCode == 401 || statusCode == 403) {
            return "review_external_auth_failed";
        }
        if (statusCode == 408) {
            return "review_timeout";
        }
        if (statusCode >= 500) {
            return "review_external_service_unavailable";
        }
        if (statusCode >= 400) {
            return "review_external_request_failed";
        }
        return "review_execution_failed";
    }

    private boolean isStateConflict(RuntimeException ex) {
        return ex instanceof CannotAcquireLockException
            || ex instanceof DeadlockLoserDataAccessException
            || ex instanceof DuplicateKeyException
            || ex instanceof OptimisticLockingFailureException
            || ex instanceof PessimisticLockingFailureException;
    }

    private boolean isTimeout(RuntimeException ex, String detail) {
        if (ex instanceof ResourceAccessException && containsTimeout(ex)) {
            return true;
        }
        return containsTimeout(ex)
            || detail.contains("timeout")
            || detail.contains("timed out")
            || detail.contains("read timed out")
            || detail.contains("connect timed out");
    }

    private boolean containsTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException || current instanceof TimeoutException) {
                return true;
            }
            String message = current.getMessage();
            if (StringUtils.hasText(message)) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("timeout") || normalized.contains("timed out")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private Integer statusCodeFromDetail(String detail) {
        int marker = detail.indexOf("status=");
        if (marker < 0) {
            return null;
        }
        int start = marker + "status=".length();
        int end = start;
        while (end < detail.length() && Character.isDigit(detail.charAt(end))) {
            end++;
        }
        if (end == start) {
            return null;
        }
        try {
            return Integer.parseInt(detail.substring(start, end));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String normalizedDetail(RuntimeException ex) {
        String message = ex == null ? "" : ex.getMessage();
        return message == null ? "" : message.toLowerCase(Locale.ROOT);
    }
}
