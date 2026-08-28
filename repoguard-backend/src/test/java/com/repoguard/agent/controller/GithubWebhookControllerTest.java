package com.repoguard.agent.controller;

import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.common.GlobalExceptionHandler;
import com.repoguard.agent.dto.ManualReviewRequest;
import com.repoguard.agent.dto.ManualReviewResponse;
import com.repoguard.agent.github.webhook.GithubPullRequestWebhookService;
import com.repoguard.agent.github.webhook.GithubWebhookProperties;
import com.repoguard.agent.github.webhook.GithubWebhookSignatureVerifier;
import com.repoguard.agent.service.ReviewService;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GithubWebhookControllerTest {

    private static final String SIGNING_KEY = "github-webhook-test-secret";
    private static final String HEAD_SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String UPDATED_HEAD_SHA = "89abcdef0123456789abcdef0123456789abcdef";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GithubWebhookProperties properties = webhookProperties();
    private final ReviewService reviewService = org.mockito.Mockito.mock(ReviewService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new GithubWebhookController(
        objectMapper,
        properties,
        new GithubWebhookSignatureVerifier(properties),
        new GithubPullRequestWebhookService(properties, reviewService)
    ))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();

    @Test
    void pullRequestOpenedCreatesReviewTaskFromWebhookPayload() throws Exception {
        when(reviewService.triggerWebhookReview(any(), any())).thenReturn(
            new ManualReviewResponse(88L, "queued", "Review task queued", false, "github_webhook", "github_webhook")
        );

        byte[] payload = pullRequestPayload("opened", false, HEAD_SHA).getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(post("/api/v1/github/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-GitHub-Event", "pull_request")
                .header("X-GitHub-Delivery", "delivery-1")
                .header("X-Hub-Signature-256", signature(payload))
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.message").value("OK"))
            .andExpect(jsonPath("$.timestamp", notNullValue()))
            .andExpect(jsonPath("$.data.status").value("queued"))
            .andExpect(jsonPath("$.data.message").value("Review task queued"))
            .andExpect(jsonPath("$.data.taskId").value(88))
            .andExpect(jsonPath("$.data.existing").value(false))
            .andExpect(jsonPath("$.data.deliveryId").value("delivery-1"))
            .andExpect(jsonPath("$.data.action").value("opened"));

        ArgumentCaptor<ManualReviewRequest> captor = ArgumentCaptor.forClass(ManualReviewRequest.class);
        verify(reviewService).triggerWebhookReview(captor.capture(), any());
        ManualReviewRequest request = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(request.organization()).isEqualTo("repo-guard-demo");
        org.assertj.core.api.Assertions.assertThat(request.repository()).isEqualTo("spring-boot-demo");
        org.assertj.core.api.Assertions.assertThat(request.prNumber()).isEqualTo(512);
        org.assertj.core.api.Assertions.assertThat(request.title()).isEqualTo("Add auto review");
        org.assertj.core.api.Assertions.assertThat(request.commit()).isEqualTo(HEAD_SHA);
        org.assertj.core.api.Assertions.assertThat(request.branch()).isEqualTo("PRAgent-test");
        org.assertj.core.api.Assertions.assertThat(request.source()).isEqualTo("github_webhook");
    }

    @Test
    void pullRequestSynchronizeKeepsQueuedResponseContractForExistingTask() throws Exception {
        when(reviewService.triggerWebhookReview(any(), any())).thenReturn(
            new ManualReviewResponse(99L, "queued", "Existing review task reused", true, "github_webhook", "github_webhook")
        );

        byte[] payload = pullRequestPayload("synchronize", false, UPDATED_HEAD_SHA).getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(post("/api/v1/github/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-GitHub-Event", "pull_request")
                .header("X-GitHub-Delivery", "delivery-sync")
                .header("X-Hub-Signature-256", signature(payload))
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.message").value("OK"))
            .andExpect(jsonPath("$.timestamp", notNullValue()))
            .andExpect(jsonPath("$.data.status").value("queued"))
            .andExpect(jsonPath("$.data.message").value("Existing review task reused"))
            .andExpect(jsonPath("$.data.taskId").value(99))
            .andExpect(jsonPath("$.data.existing").value(true))
            .andExpect(jsonPath("$.data.deliveryId").value("delivery-sync"))
            .andExpect(jsonPath("$.data.action").value("synchronize"));
    }

    @Test
    void nonPullRequestEventIsAcceptedAndSkipped() throws Exception {
        byte[] payload = "{\"zen\":\"Keep it logically awesome.\"}".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/v1/github/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-GitHub-Event", "ping")
                .header("X-GitHub-Delivery", "delivery-2")
                .header("X-Hub-Signature-256", signature(payload))
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.message").value("OK"))
            .andExpect(jsonPath("$.timestamp", notNullValue()))
            .andExpect(jsonPath("$.data.status").value("skipped"))
            .andExpect(jsonPath("$.data.message").value("GitHub event is ignored"))
            .andExpect(jsonPath("$.data.taskId").doesNotExist())
            .andExpect(jsonPath("$.data.existing").doesNotExist())
            .andExpect(jsonPath("$.data.deliveryId").value("delivery-2"))
            .andExpect(jsonPath("$.data.action").doesNotExist());

        verify(reviewService, never()).triggerManualReview(any());
    }

    @Test
    void draftPullRequestIsSkippedByDefault() throws Exception {
        byte[] payload = pullRequestPayload("opened", true, HEAD_SHA).getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/v1/github/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-GitHub-Event", "pull_request")
                .header("X-GitHub-Delivery", "delivery-3")
                .header("X-Hub-Signature-256", signature(payload))
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.message").value("OK"))
            .andExpect(jsonPath("$.timestamp", notNullValue()))
            .andExpect(jsonPath("$.data.status").value("skipped"))
            .andExpect(jsonPath("$.data.message").value("Draft pull request is ignored"))
            .andExpect(jsonPath("$.data.taskId").doesNotExist())
            .andExpect(jsonPath("$.data.existing").doesNotExist())
            .andExpect(jsonPath("$.data.deliveryId").value("delivery-3"))
            .andExpect(jsonPath("$.data.action").value("opened"));

        verify(reviewService, never()).triggerManualReview(any());
    }

    @Test
    void ignoredPullRequestActionKeepsSkippedResponseContract() throws Exception {
        byte[] payload = pullRequestPayload("closed", false, HEAD_SHA).getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/v1/github/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-GitHub-Event", "pull_request")
                .header("X-GitHub-Delivery", "delivery-closed")
                .header("X-Hub-Signature-256", signature(payload))
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.message").value("OK"))
            .andExpect(jsonPath("$.timestamp", notNullValue()))
            .andExpect(jsonPath("$.data.status").value("skipped"))
            .andExpect(jsonPath("$.data.message").value("GitHub pull_request action is ignored"))
            .andExpect(jsonPath("$.data.taskId").doesNotExist())
            .andExpect(jsonPath("$.data.existing").doesNotExist())
            .andExpect(jsonPath("$.data.deliveryId").value("delivery-closed"))
            .andExpect(jsonPath("$.data.action").value("closed"));

        verify(reviewService, never()).triggerManualReview(any());
    }

    @Test
    void unmonitoredRepositoryIsSkippedWhenAllowListIsConfigured() throws Exception {
        properties.setAllowedRepositories(java.util.List.of("repo-guard-demo/test-repo"));
        byte[] payload = pullRequestPayload("opened", false, HEAD_SHA).getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/v1/github/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-GitHub-Event", "pull_request")
                .header("X-GitHub-Delivery", "delivery-4")
                .header("X-Hub-Signature-256", signature(payload))
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.message").value("OK"))
            .andExpect(jsonPath("$.timestamp", notNullValue()))
            .andExpect(jsonPath("$.data.status").value("skipped"))
            .andExpect(jsonPath("$.data.message").value("GitHub repository is not monitored"))
            .andExpect(jsonPath("$.data.taskId").doesNotExist())
            .andExpect(jsonPath("$.data.existing").doesNotExist())
            .andExpect(jsonPath("$.data.deliveryId").value("delivery-4"))
            .andExpect(jsonPath("$.data.action").value("opened"));

        verify(reviewService, never()).triggerManualReview(any());
    }

    @Test
    void unmonitoredHeadBranchIsSkippedByDefault() throws Exception {
        byte[] payload = pullRequestPayload("opened", false, HEAD_SHA, "feature/other").getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/v1/github/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-GitHub-Event", "pull_request")
                .header("X-GitHub-Delivery", "delivery-5")
                .header("X-Hub-Signature-256", signature(payload))
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.message").value("OK"))
            .andExpect(jsonPath("$.timestamp", notNullValue()))
            .andExpect(jsonPath("$.data.status").value("skipped"))
            .andExpect(jsonPath("$.data.message").value("GitHub pull request head branch is not monitored"))
            .andExpect(jsonPath("$.data.taskId").doesNotExist())
            .andExpect(jsonPath("$.data.existing").doesNotExist())
            .andExpect(jsonPath("$.data.deliveryId").value("delivery-5"))
            .andExpect(jsonPath("$.data.action").value("opened"));

        verify(reviewService, never()).triggerManualReview(any());
    }

    @Test
    void invalidSignatureIsRejectedBeforePayloadHandling() throws Exception {
        byte[] payload = pullRequestPayload("opened", false, HEAD_SHA).getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/v1/github/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-GitHub-Event", "pull_request")
                .header("X-Hub-Signature-256", "sha256=invalid")
                .content(payload))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verify(reviewService, never()).triggerManualReview(any());
    }

    @Test
    void nonPullRequestEventSkipsPayloadParsingAfterSignatureVerification() throws Exception {
        byte[] payload = "not-json".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/v1/github/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-GitHub-Event", "ping")
                .header("X-GitHub-Delivery", "delivery-invalid-json")
                .header("X-Hub-Signature-256", signature(payload))
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.message").value("OK"))
            .andExpect(jsonPath("$.timestamp", notNullValue()))
            .andExpect(jsonPath("$.data.status").value("skipped"))
            .andExpect(jsonPath("$.data.message").value("GitHub event is ignored"))
            .andExpect(jsonPath("$.data.taskId").doesNotExist())
            .andExpect(jsonPath("$.data.existing").doesNotExist())
            .andExpect(jsonPath("$.data.deliveryId").value("delivery-invalid-json"))
            .andExpect(jsonPath("$.data.action").doesNotExist());

        verify(reviewService, never()).triggerManualReview(any());
    }

    @Test
    void oversizedPayloadIsRejectedBeforeSignatureVerification() throws Exception {
        properties.setMaxPayloadBytes(4);
        byte[] payload = "{\"zen\":\"too large\"}".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/v1/github/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-GitHub-Event", "ping")
                .header("X-Hub-Signature-256", "sha256=invalid")
                .content(payload))
            .andExpect(status().isContentTooLarge())
            .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));

        verify(reviewService, never()).triggerManualReview(any());
    }

    private GithubWebhookProperties webhookProperties() {
        GithubWebhookProperties result = new GithubWebhookProperties();
        result.setSecret(SIGNING_KEY);
        result.setAllowedHeadBranches(java.util.List.of("PRAgent-test"));
        return result;
    }

    private String pullRequestPayload(String action, boolean draft, String sha) {
        return pullRequestPayload(action, draft, sha, "PRAgent-test");
    }

    private String pullRequestPayload(String action, boolean draft, String sha, String branch) {
        return """
            {
              "action": "%s",
              "repository": {
                "name": "spring-boot-demo",
                "owner": { "login": "repo-guard-demo" }
              },
              "pull_request": {
                "number": 512,
                "title": "Add auto review",
                "draft": %s,
                "updated_at": "2026-08-15T03:00:00Z",
                "head": {
                  "ref": "%s",
                  "sha": "%s"
                }
              }
            }
            """.formatted(action, draft, branch, sha);
    }

    private String signature(byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SIGNING_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));
    }

}
