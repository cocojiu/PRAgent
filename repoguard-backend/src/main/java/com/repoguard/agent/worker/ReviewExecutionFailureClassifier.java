package com.repoguard.agent.worker;

import com.repoguard.agent.external.ExternalCallException;
import com.repoguard.agent.external.ExternalFailureSignals;
import com.repoguard.agent.github.GithubPullRequestHeadChangedException;
import com.repoguard.agent.observability.ReviewFailureCategoryResolver;
import com.repoguard.agent.tenancy.TenantInactiveException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class ReviewExecutionFailureClassifier implements ReviewFailureCategoryResolver {

    @Override
    public String failureCategory(RuntimeException ex) {
        if (ex instanceof TenantInactiveException) {
            return "review_tenant_inactive";
        }
        if (ex instanceof GithubPullRequestHeadChangedException) {
            return "review_superseded";
        }
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
        String detail = ExternalFailureSignals.normalizedDetail(ex);
        Integer statusCode = ExternalFailureSignals.statusCodeFromDetail(detail);
        if (statusCode != null) {
            return httpFailureCategory(statusCode);
        }
        if (ExternalFailureSignals.hasRateLimitSignal(detail)) {
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
        return ex instanceof DuplicateKeyException
            || ex instanceof OptimisticLockingFailureException
            || ex instanceof PessimisticLockingFailureException;
    }

    private boolean isTimeout(RuntimeException ex, String detail) {
        if (ex instanceof ResourceAccessException && ExternalFailureSignals.hasTimeoutSignal(ex, detail, true)) {
            return true;
        }
        return ExternalFailureSignals.hasTimeoutSignal(ex, detail, true);
    }
}
