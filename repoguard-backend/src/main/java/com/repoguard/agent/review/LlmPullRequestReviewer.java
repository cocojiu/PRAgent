package com.repoguard.agent.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.config.ReviewPolicyProvider;
import com.repoguard.agent.config.ReviewPolicySettings;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalCallErrorClassifier;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.github.GithubPullRequestDiff;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class LlmPullRequestReviewer implements PullRequestReviewer, LlmReviewCaller {

    private final ReviewPolicyProvider reviewPolicyProvider;
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;
    private final RepoGuardMetrics metrics;
    private final ExternalCallResilience resilience;
    private final LlmReviewPromptBuilder promptBuilder;
    private final LlmReviewPipeline reviewPipeline;
    private final LlmHttpResponseReader responseReader;

    @Autowired
    public LlmPullRequestReviewer(
        ReviewPolicyProvider reviewPolicyProvider,
        RuleBasedPullRequestReviewer ruleBasedReviewer,
        RestClient.Builder restClientBuilder,
        ObjectMapper objectMapper,
        RepoGuardMetrics metrics,
        ExternalCallResilience resilience,
        LlmReviewPromptBuilder promptBuilder,
        LlmRuleReviewMerger reviewMerger,
        LlmReviewPipeline reviewPipeline
    ) {
        this(
            reviewPolicyProvider,
            restClientBuilder,
            objectMapper,
            metrics,
            resilience,
            promptBuilder,
            reviewPipeline,
            new LlmHttpResponseReader()
        );
    }

    public LlmPullRequestReviewer(
        ReviewPolicyProvider reviewPolicyProvider,
        RuleBasedPullRequestReviewer ruleBasedReviewer,
        RestClient.Builder restClientBuilder,
        ObjectMapper objectMapper,
        RepoGuardMetrics metrics,
        ExternalCallResilience resilience
    ) {
        this(
            reviewPolicyProvider,
            restClientBuilder,
            objectMapper,
            metrics,
            resilience,
            null,
            new LlmReviewPipeline(ruleBasedReviewer, null, null, objectMapper, metrics, new PullRequestDiffChunker()),
            new LlmHttpResponseReader()
        );
    }

    LlmPullRequestReviewer(
        ReviewPolicyProvider reviewPolicyProvider,
        RuleBasedPullRequestReviewer ruleBasedReviewer,
        RestClient.Builder restClientBuilder,
        ObjectMapper objectMapper,
        RepoGuardMetrics metrics,
        ExternalCallResilience resilience,
        PullRequestDiffChunker diffChunker
    ) {
        this(
            reviewPolicyProvider,
            restClientBuilder,
            objectMapper,
            metrics,
            resilience,
            null,
            new LlmReviewPipeline(ruleBasedReviewer, null, null, objectMapper, metrics, diffChunker),
            new LlmHttpResponseReader()
        );
    }

    LlmPullRequestReviewer(
        ReviewPolicyProvider reviewPolicyProvider,
        RuleBasedPullRequestReviewer ruleBasedReviewer,
        RestClient.Builder restClientBuilder,
        ObjectMapper objectMapper,
        RepoGuardMetrics metrics,
        ExternalCallResilience resilience,
        PullRequestDiffChunker diffChunker,
        LlmReviewPromptBuilder promptBuilder,
        LlmRuleReviewMerger reviewMerger
    ) {
        this(
            reviewPolicyProvider,
            restClientBuilder,
            objectMapper,
            metrics,
            resilience,
            promptBuilder,
            new LlmReviewPipeline(ruleBasedReviewer, promptBuilder, reviewMerger, objectMapper, metrics, diffChunker),
            new LlmHttpResponseReader()
        );
    }

    LlmPullRequestReviewer(
        ReviewPolicyProvider reviewPolicyProvider,
        RestClient.Builder restClientBuilder,
        ObjectMapper objectMapper,
        RepoGuardMetrics metrics,
        ExternalCallResilience resilience,
        LlmReviewPromptBuilder promptBuilder,
        LlmReviewPipeline reviewPipeline,
        LlmHttpResponseReader responseReader
    ) {
        this.reviewPolicyProvider = reviewPolicyProvider;
        this.restClientBuilder = restClientBuilder;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must be provided");
        this.metrics = metrics;
        this.resilience = resilience;
        this.promptBuilder = promptBuilder == null ? new LlmReviewPromptBuilder() : promptBuilder;
        this.reviewPipeline = reviewPipeline;
        this.responseReader = responseReader;
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
        RestClient restClient = restClientBuilder
            .clone()
            .baseUrl(settings.baseUrl().trim())
            .requestFactory(requestFactory(settings.timeoutSeconds()))
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
            byte[] response = executeLlm("chat_completions", () -> restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey.trim())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(payload)
                .exchange((request, clientResponse) -> responseReader.readSuccessfulBody(
                    clientResponse,
                    "LLM request failed"
                )));
            if (metrics != null) {
                metrics.llmRequestDuration(Duration.ofNanos(System.nanoTime() - startedAt), "success");
            }
            return extractLlmCallResult(response == null ? "" : new String(response, StandardCharsets.UTF_8));
        } catch (RuntimeException ex) {
            var classified = ExternalCallErrorClassifier.llm(ex);
            if (metrics != null) {
                metrics.externalCallFailed(classified);
                metrics.llmRequestDuration(Duration.ofNanos(System.nanoTime() - startedAt), "failed");
            }
            throw classified;
        }
    }

    private LlmCallResult extractLlmCallResult(String response) {
        try {
            JsonNode root = objectMapper.readTree(response == null ? "" : response);
            return new LlmCallResult(
                root.at("/choices/0/message/content").asText(""),
                intValue(root.at("/usage/prompt_tokens")),
                intValue(root.at("/usage/completion_tokens")),
                intValue(root.at("/usage/total_tokens"))
            );
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to parse LLM HTTP response", ex);
        }
    }

    private Integer intValue(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() || !node.canConvertToInt() ? null : node.asInt();
    }

    private SimpleClientHttpRequestFactory requestFactory(Integer timeoutSeconds) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds == null ? 60 : timeoutSeconds));
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return requestFactory;
    }

    private <T> T executeLlm(String operation, java.util.function.Supplier<T> supplier) {
        return resilience == null ? supplier.get() : resilience.llm(operation, supplier);
    }
}
