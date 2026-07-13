package com.repoguard.agent.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.repoguard.agent.config.ReviewPolicyProvider;
import com.repoguard.agent.config.ReviewPolicySettings;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalCallErrorClassifier;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.external.ExternalHttpRequestFactory;
import com.repoguard.agent.external.ExternalHttpJsonResponseReader;
import com.repoguard.agent.external.OutboundEndpointPolicy;
import com.repoguard.agent.external.OutboundEndpointType;
import com.repoguard.agent.github.GithubPullRequestDiff;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class LlmPullRequestReviewer implements PullRequestReviewer, LlmReviewCaller {

    private final ReviewPolicyProvider reviewPolicyProvider;
    private final RestClient.Builder restClientBuilder;
    private final RepoGuardMetrics metrics;
    private final ExternalCallResilience resilience;
    private final LlmReviewPromptBuilder promptBuilder;
    private final LlmReviewPipeline reviewPipeline;
    private final ExternalHttpJsonResponseReader responseReader;
    private final LlmChatCompletionResponseExtractor responseExtractor;
    private final OutboundEndpointPolicy endpointPolicy;

    @Autowired
    public LlmPullRequestReviewer(
        ReviewPolicyProvider reviewPolicyProvider,
        RestClient.Builder restClientBuilder,
        RepoGuardMetrics metrics,
        ExternalCallResilience resilience,
        LlmReviewPromptBuilder promptBuilder,
        LlmReviewPipeline reviewPipeline,
        ExternalHttpJsonResponseReader responseReader,
        LlmChatCompletionResponseExtractor responseExtractor,
        OutboundEndpointPolicy endpointPolicy
    ) {
        this(reviewPolicyProvider, restClientBuilder, metrics, resilience, promptBuilder, reviewPipeline, responseReader, responseExtractor, endpointPolicy, true);
    }

    public LlmPullRequestReviewer(
        ReviewPolicyProvider reviewPolicyProvider,
        RestClient.Builder restClientBuilder,
        RepoGuardMetrics metrics,
        ExternalCallResilience resilience,
        LlmReviewPromptBuilder promptBuilder,
        LlmReviewPipeline reviewPipeline,
        ExternalHttpJsonResponseReader responseReader,
        LlmChatCompletionResponseExtractor responseExtractor
    ) {
        this(reviewPolicyProvider, restClientBuilder, metrics, resilience, promptBuilder, reviewPipeline, responseReader, responseExtractor, null, true);
    }

    private LlmPullRequestReviewer(
        ReviewPolicyProvider reviewPolicyProvider,
        RestClient.Builder restClientBuilder,
        RepoGuardMetrics metrics,
        ExternalCallResilience resilience,
        LlmReviewPromptBuilder promptBuilder,
        LlmReviewPipeline reviewPipeline,
        ExternalHttpJsonResponseReader responseReader,
        LlmChatCompletionResponseExtractor responseExtractor,
        OutboundEndpointPolicy endpointPolicy,
        boolean ignored
    ) {
        this.reviewPolicyProvider = Objects.requireNonNull(reviewPolicyProvider, "reviewPolicyProvider");
        this.restClientBuilder = Objects.requireNonNull(restClientBuilder, "restClientBuilder");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.resilience = Objects.requireNonNull(resilience, "resilience");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.reviewPipeline = Objects.requireNonNull(reviewPipeline, "reviewPipeline");
        this.responseReader = Objects.requireNonNull(responseReader, "responseReader");
        this.responseExtractor = Objects.requireNonNull(responseExtractor, "responseExtractor");
        this.endpointPolicy = endpointPolicy;
    }

    @Override
    public ReviewResult review(ReviewTask task, GithubPullRequestDiff diff) {
        long startedAt = System.nanoTime();
        ReviewPolicySettings settings = reviewPolicyProvider.getSettings();
        String promptSummary = promptBuilder.promptSummary(diff);
        return reviewPipeline.execute(
            new ReviewPipelineContext(task, diff, settings, promptSummary, startedAt, this)
        );
    }

    @Override
    public LlmCallResult callLlm(ReviewPolicySettings settings, ReviewTask task, GithubPullRequestDiff diff) {
        long startedAt = System.nanoTime();
        if (endpointPolicy != null) {
            endpointPolicy.validate(OutboundEndpointType.LLM, settings.baseUrl());
        }
        RestClient restClient = restClientBuilder
            .clone()
            .baseUrl(settings.baseUrl().trim())
            .requestFactory(ExternalHttpRequestFactory.sameTimeoutSeconds(settings.timeoutSeconds(), 60))
            .build();
        String apiKey = settings.apiKey();

        Map<String, Object> payload = Map.of(
            "model", settings.modelName(),
            "temperature", settings.temperature(),
            "max_tokens", settings.maxTokens(),
            "messages", List.of(
                Map.of("role", "system", "content", "你是资深代码审查助手，只输出严格 JSON。"),
                Map.of("role", "user", "content", promptBuilder.buildPrompt(task, diff))
            )
        );

        try {
            JsonNode response = executeLlm("chat_completions", () -> restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey.trim())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(payload)
                .exchange((request, clientResponse) -> responseReader.readSuccessfulTree(
                    clientResponse,
                    "LLM request failed"
                )));
            metrics.llmRequestDuration(Duration.ofNanos(System.nanoTime() - startedAt), "success");
            return extractLlmCallResult(response);
        } catch (RuntimeException ex) {
            var classified = ExternalCallErrorClassifier.llm(ex);
            metrics.externalCallFailed(classified);
            metrics.llmRequestDuration(Duration.ofNanos(System.nanoTime() - startedAt), "failed");
            throw classified;
        }
    }

    private LlmCallResult extractLlmCallResult(JsonNode root) {
        try {
            LlmChatCompletionResponse response = responseExtractor.extract(root);
            return new LlmCallResult(
                response.content(),
                response.promptTokens(),
                response.completionTokens(),
                response.totalTokens()
            );
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to parse LLM HTTP response", ex);
        }
    }

    private <T> T executeLlm(String operation, java.util.function.Supplier<T> supplier) {
        return resilience.llm(operation, supplier);
    }
}
