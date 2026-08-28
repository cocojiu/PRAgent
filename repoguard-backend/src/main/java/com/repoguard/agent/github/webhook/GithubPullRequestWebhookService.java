package com.repoguard.agent.github.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.ManualReviewRequest;
import com.repoguard.agent.dto.ManualReviewResponse;
import com.repoguard.agent.review.ReviewTaskSource;
import com.repoguard.agent.service.ReviewService;
import com.repoguard.agent.tenancy.TenantContext;
import com.repoguard.agent.tenancy.TenantRepositoryBinding;
import com.repoguard.agent.tenancy.TenantRepositoryResolver;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class GithubPullRequestWebhookService {

    private final GithubWebhookProperties properties;
    private final ReviewService reviewService;
    private final TenantRepositoryResolver tenantRepositoryResolver;

    @Autowired
    public GithubPullRequestWebhookService(
        GithubWebhookProperties properties,
        ReviewService reviewService,
        TenantRepositoryResolver tenantRepositoryResolver
    ) {
        this.properties = properties;
        this.reviewService = reviewService;
        this.tenantRepositoryResolver = tenantRepositoryResolver;
    }

    public GithubPullRequestWebhookService(GithubWebhookProperties properties, ReviewService reviewService) {
        this.properties = properties;
        this.reviewService = reviewService;
        this.tenantRepositoryResolver = null;
    }

    public GithubWebhookResponse handlePullRequest(JsonNode payload, String deliveryId) {
        if (!properties.isEnabled()) {
            return GithubWebhookResponse.skipped("GitHub webhook auto review is disabled", deliveryId, action(payload));
        }
        String action = action(payload);
        if (!isAllowedAction(action)) {
            return GithubWebhookResponse.skipped("GitHub pull_request action is ignored", deliveryId, action);
        }
        JsonNode pullRequest = requiredObject(payload, "pull_request");
        if (properties.isIgnoreDraft() && pullRequest.path("draft").asBoolean(false)) {
            return GithubWebhookResponse.skipped("Draft pull request is ignored", deliveryId, action);
        }

        JsonNode repository = requiredObject(payload, "repository");
        JsonNode owner = requiredObject(repository, "owner");
        JsonNode head = requiredObject(pullRequest, "head");

        String organization = requiredText(owner, "login");
        String repositoryName = requiredText(repository, "name");
        if (!isAllowedRepository(organization, repositoryName)) {
            return GithubWebhookResponse.skipped("GitHub repository is not monitored", deliveryId, action);
        }
        String branch = textOrNull(head, "ref");
        if (!isAllowedHeadBranch(branch)) {
            return GithubWebhookResponse.skipped("GitHub pull request head branch is not monitored", deliveryId, action);
        }
        Integer prNumber = requiredInt(pullRequest, "number");
        String title = textOrNull(pullRequest, "title");
        String commit = requiredText(head, "sha");
        LocalDateTime headUpdatedAt = requiredTimestamp(pullRequest, "updated_at");
        Long installationId = optionalLong(payload.get("installation"), "id");

        ManualReviewRequest request = new ManualReviewRequest(
            organization,
            repositoryName,
            prNumber,
            title,
            commit,
            branch,
            ReviewTaskSource.GITHUB_WEBHOOK.dtoCode()
        );
        ManualReviewResponse response = triggerInTenant(
            request,
            headUpdatedAt,
            organization,
            repositoryName,
            installationId
        );
        return new GithubWebhookResponse(
            response.status(),
            response.message(),
            response.taskId(),
            response.existing(),
            deliveryId,
            action
        );
    }

    private ManualReviewResponse triggerInTenant(
        ManualReviewRequest request,
        LocalDateTime headUpdatedAt,
        String organization,
        String repository,
        Long installationId
    ) {
        if (tenantRepositoryResolver == null) {
            return reviewService.triggerWebhookReview(request, headUpdatedAt);
        }
        TenantRepositoryBinding binding = tenantRepositoryResolver.resolve(
            organization,
            repository,
            installationId
        );
        try (TenantContext.Scope _ = TenantContext.withTenant(binding.tenantId())) {
            return reviewService.triggerWebhookReview(request, headUpdatedAt);
        }
    }

    private boolean isAllowedAction(String action) {
        if (!StringUtils.hasText(action)) {
            return false;
        }
        String normalized = action.toLowerCase(Locale.ROOT);
        return properties.getAllowedActions().stream()
            .filter(StringUtils::hasText)
            .map(value -> value.trim().toLowerCase(Locale.ROOT))
            .anyMatch(normalized::equals);
    }

    private boolean isAllowedRepository(String organization, String repository) {
        if (properties.getAllowedRepositories().isEmpty()) {
            return true;
        }
        String fullName = (organization + "/" + repository).toLowerCase(Locale.ROOT);
        return properties.getAllowedRepositories().stream()
            .filter(StringUtils::hasText)
            .map(value -> value.trim().toLowerCase(Locale.ROOT))
            .anyMatch(fullName::equals);
    }

    private boolean isAllowedHeadBranch(String branch) {
        if (properties.getAllowedHeadBranches().isEmpty()) {
            return true;
        }
        if (!StringUtils.hasText(branch)) {
            return false;
        }
        String normalized = branch.trim().toLowerCase(Locale.ROOT);
        return properties.getAllowedHeadBranches().stream()
            .filter(StringUtils::hasText)
            .map(value -> value.trim().toLowerCase(Locale.ROOT))
            .anyMatch(normalized::equals);
    }

    private String action(JsonNode payload) {
        return textOrNull(payload, "action");
    }

    private JsonNode requiredObject(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);
        if (value == null || !value.isObject()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "GitHub webhook payload is missing " + fieldName);
        }
        return value;
    }

    private String requiredText(JsonNode node, String fieldName) {
        String value = textOrNull(node, fieldName);
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "GitHub webhook payload is missing " + fieldName);
        }
        return value;
    }

    private Integer requiredInt(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);
        if (value == null || !value.canConvertToInt()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "GitHub webhook payload is missing " + fieldName);
        }
        return value.asInt();
    }

    private Long optionalLong(JsonNode node, String fieldName) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.canConvertToLong() || value.asLong() < 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "GitHub webhook payload has invalid " + fieldName);
        }
        return value.asLong();
    }

    private LocalDateTime requiredTimestamp(JsonNode node, String fieldName) {
        String value = requiredText(node, fieldName);
        try {
            return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "GitHub webhook payload has invalid " + fieldName);
        }
    }

    private String textOrNull(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText(null);
        return StringUtils.hasText(text) ? text.trim() : null;
    }
}
