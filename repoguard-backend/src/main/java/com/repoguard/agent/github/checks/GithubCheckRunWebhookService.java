package com.repoguard.agent.github.checks;

import com.fasterxml.jackson.databind.JsonNode;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.ReviewRetryResponse;
import com.repoguard.agent.github.webhook.GithubWebhookResponse;
import com.repoguard.agent.review.ReviewTaskStatus;
import com.repoguard.agent.review.task.ReviewTaskRetryService;
import com.repoguard.agent.entity.GithubCheckRun;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.GithubCheckRunMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.tenancy.TenantContext;
import com.repoguard.agent.tenancy.TenantRepositoryBinding;
import com.repoguard.agent.tenancy.TenantRepositoryResolver;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class GithubCheckRunWebhookService {

    private final GithubCheckRunProperties properties;
    private final GithubCheckRunMapper checkRunMapper;
    private final ReviewTaskMapper taskMapper;
    private final ReviewTaskRetryService retryService;
    private final TenantRepositoryResolver tenantRepositoryResolver;

    public GithubCheckRunWebhookService(
        GithubCheckRunProperties properties,
        GithubCheckRunMapper checkRunMapper,
        ReviewTaskMapper taskMapper,
        ReviewTaskRetryService retryService,
        TenantRepositoryResolver tenantRepositoryResolver
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.checkRunMapper = Objects.requireNonNull(checkRunMapper, "checkRunMapper");
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper");
        this.retryService = Objects.requireNonNull(retryService, "retryService");
        this.tenantRepositoryResolver = Objects.requireNonNull(tenantRepositoryResolver, "tenantRepositoryResolver");
    }

    public GithubWebhookResponse handle(JsonNode payload, String deliveryId) {
        String action = text(payload, "action");
        if (!properties.isEnabled()) {
            return GithubWebhookResponse.skipped("GitHub Check Run merge gate is disabled", deliveryId, action);
        }
        if (!"rerequested".equalsIgnoreCase(action)) {
            return GithubWebhookResponse.skipped("GitHub Check Run action is ignored", deliveryId, action);
        }
        JsonNode checkRunNode = requiredObject(payload, "check_run");
        long githubCheckRunId = requiredLong(checkRunNode, "id");
        String externalId = requiredText(checkRunNode, "external_id");
        String name = requiredText(checkRunNode, "name");
        String headSha = requiredText(checkRunNode, "head_sha");
        if (!properties.getName().equals(name)) {
            return GithubWebhookResponse.skipped("GitHub Check Run name is not managed by RepoGuard", deliveryId, action);
        }
        JsonNode repository = requiredObject(payload, "repository");
        JsonNode owner = requiredObject(repository, "owner");
        String organization = requiredText(owner, "login");
        String repositoryName = requiredText(repository, "name");
        TenantRepositoryBinding binding = tenantRepositoryResolver.resolve(
            organization,
            repositoryName,
            optionalLong(payload.get("installation"), "id")
        );
        try (TenantContext.Scope _ = TenantContext.withTenant(binding.tenantId())) {
            GithubCheckRun record = checkRunMapper.selectByGithubCheckRunId(githubCheckRunId);
            if (record == null || !externalId.equals(record.getExternalId()) || !name.equals(record.getName())) {
                return GithubWebhookResponse.skipped("GitHub Check Run is not registered by RepoGuard", deliveryId, action);
            }
            ReviewTask task = taskMapper.selectById(record.getTaskId());
            if (task == null || !organization.equalsIgnoreCase(task.getOrganization())
                || !repositoryName.equalsIgnoreCase(task.getRepository())) {
                return GithubWebhookResponse.skipped("GitHub Check Run task is not found", deliveryId, action);
            }
            ReviewTaskStatus status = ReviewTaskStatus.from(task.getStatus());
            if (status == ReviewTaskStatus.QUEUED || status == ReviewTaskStatus.REVIEWING) {
                return GithubWebhookResponse.skipped("Review task is already running", deliveryId, action);
            }
            ReviewRetryResponse response = retryService.rerunFromGithubCheck(task.getId(), headSha);
            return new GithubWebhookResponse(
                response.status(), response.message(), response.taskId(), false, deliveryId, action
            );
        }
    }

    private JsonNode requiredObject(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isObject()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "GitHub webhook payload is missing " + field);
        }
        return value;
    }

    private String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "GitHub webhook payload is missing " + field);
        }
        return value;
    }

    private long requiredLong(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.canConvertToLong() || value.asLong() < 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "GitHub webhook payload has invalid " + field);
        }
        return value.asLong();
    }

    private Long optionalLong(JsonNode node, String field) {
        if (node == null || !node.isObject() || node.get(field) == null || node.get(field).isNull()) {
            return null;
        }
        return requiredLong(node, field);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String result = value.asText(null);
        return StringUtils.hasText(result) ? result.trim() : null;
    }
}
