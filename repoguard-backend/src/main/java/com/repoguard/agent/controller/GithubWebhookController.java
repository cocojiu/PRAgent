package com.repoguard.agent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.github.webhook.GithubPullRequestWebhookService;
import com.repoguard.agent.github.webhook.GithubWebhookProperties;
import com.repoguard.agent.github.webhook.GithubWebhookRateLimiter;
import com.repoguard.agent.github.webhook.GithubWebhookResponse;
import com.repoguard.agent.github.webhook.GithubWebhookSignatureVerifier;
import com.repoguard.agent.github.webhook.GithubWebhookDeliveryTracker;
import com.repoguard.agent.github.checks.GithubCheckRunWebhookService;
import com.repoguard.agent.security.AllowAnonymous;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

@RestController
@RequestMapping("/api/v1/github/webhooks")
@ApiRuntimeEnabled
public class GithubWebhookController {

    private final ObjectMapper objectMapper;
    private final GithubWebhookProperties properties;
    private final GithubWebhookSignatureVerifier signatureVerifier;
    private final GithubPullRequestWebhookService pullRequestWebhookService;
    private final GithubWebhookRateLimiter rateLimiter;
    private final GithubCheckRunWebhookService checkRunWebhookService;
    private final GithubWebhookDeliveryTracker deliveryTracker;

    @Autowired
    public GithubWebhookController(
        ObjectMapper objectMapper,
        GithubWebhookProperties properties,
        GithubWebhookSignatureVerifier signatureVerifier,
        GithubPullRequestWebhookService pullRequestWebhookService,
        GithubWebhookRateLimiter rateLimiter,
        ObjectProvider<GithubCheckRunWebhookService> checkRunWebhookServiceProvider,
        ObjectProvider<GithubWebhookDeliveryTracker> deliveryTrackerProvider
    ) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.signatureVerifier = signatureVerifier;
        this.pullRequestWebhookService = pullRequestWebhookService;
        this.rateLimiter = rateLimiter;
        this.checkRunWebhookService = checkRunWebhookServiceProvider.getIfAvailable();
        this.deliveryTracker = deliveryTrackerProvider.getIfAvailable();
    }

    public GithubWebhookController(
        ObjectMapper objectMapper,
        GithubWebhookProperties properties,
        GithubWebhookSignatureVerifier signatureVerifier,
        GithubPullRequestWebhookService pullRequestWebhookService
    ) {
        this(objectMapper, properties, signatureVerifier, pullRequestWebhookService, null,
            (GithubCheckRunWebhookService) null, null);
    }

    public GithubWebhookController(
        ObjectMapper objectMapper,
        GithubWebhookProperties properties,
        GithubWebhookSignatureVerifier signatureVerifier,
        GithubPullRequestWebhookService pullRequestWebhookService,
        GithubWebhookRateLimiter rateLimiter
    ) {
        this(objectMapper, properties, signatureVerifier, pullRequestWebhookService, rateLimiter,
            (GithubCheckRunWebhookService) null, null);
    }

    private GithubWebhookController(
        ObjectMapper objectMapper,
        GithubWebhookProperties properties,
        GithubWebhookSignatureVerifier signatureVerifier,
        GithubPullRequestWebhookService pullRequestWebhookService,
        GithubWebhookRateLimiter rateLimiter,
        GithubCheckRunWebhookService checkRunWebhookService,
        GithubWebhookDeliveryTracker deliveryTracker
    ) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.signatureVerifier = signatureVerifier;
        this.pullRequestWebhookService = pullRequestWebhookService;
        this.rateLimiter = rateLimiter;
        this.checkRunWebhookService = checkRunWebhookService;
        this.deliveryTracker = deliveryTracker;
    }

    @AllowAnonymous
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<GithubWebhookResponse> receive(
        @RequestHeader(name = "X-GitHub-Event", required = false) String event,
        @RequestHeader(name = "X-GitHub-Delivery", required = false) String deliveryId,
        @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
        @RequestBody byte[] payload
    ) {
        JsonNode root = null;
        String repository = null;
        try {
            validatePayloadSize(payload);
            signatureVerifier.verify(signature, payload);
            if (!"pull_request".equals(event) && !"check_run".equals(event)) {
                GithubWebhookResponse response = GithubWebhookResponse.skipped(
                    "GitHub event is ignored", deliveryId, null
                );
                recordDelivery(deliveryId, event, repository, "accepted_ignored");
                return ApiResponse.ok(response);
            }
            root = parsePayload(payload);
            repository = repositoryName(root);
            if (rateLimiter != null) {
                rateLimiter.requireRepository(repository);
            }
            GithubWebhookResponse response;
            if ("check_run".equals(event)) {
                if (checkRunWebhookService == null) {
                    response = GithubWebhookResponse.skipped(
                        "GitHub Check Run handler is unavailable", deliveryId, null
                    );
                } else {
                    response = checkRunWebhookService.handle(root, deliveryId);
                }
            } else {
                response = pullRequestWebhookService.handlePullRequest(root, deliveryId);
            }
            recordDelivery(deliveryId, event, repository, "accepted_" + response.status());
            return ApiResponse.ok(response);
        } catch (RuntimeException exception) {
            String status = exception instanceof BusinessException businessException
                ? "rejected_" + businessException.getErrorCode().code()
                : "failed";
            recordDelivery(deliveryId, event, repository, status);
            throw exception;
        }
    }

    private void recordDelivery(String deliveryId, String event, String repository, String status) {
        if (deliveryTracker != null) {
            deliveryTracker.record(deliveryId, event, repository, status);
        }
    }

    private String repositoryName(JsonNode root) {
        String fullName = root.path("repository").path("full_name").asText(null);
        if (StringUtils.hasText(fullName)) {
            return fullName;
        }
        String owner = root.path("repository").path("owner").path("login").asText("unknown");
        String repository = root.path("repository").path("name").asText("unknown");
        return owner + "/" + repository;
    }

    private JsonNode parsePayload(byte[] payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "GitHub webhook payload is not valid JSON");
        }
    }

    private void validatePayloadSize(byte[] payload) {
        if (payload != null && payload.length > properties.getMaxPayloadBytes()) {
            throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "GitHub webhook payload exceeds max size");
        }
    }
}
