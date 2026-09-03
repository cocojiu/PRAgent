package com.repoguard.agent.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.repoguard.agent.review.ReviewPolicyProvider;
import com.repoguard.agent.review.ReviewPolicySettings;
import com.repoguard.agent.review.quality.LlmModelReleaseService;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalCallErrorClassifier;
import com.repoguard.agent.external.ExternalCallException;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.external.ExternalHttpRequestFactory;
import com.repoguard.agent.external.ExternalHttpJsonResponseReader;
import com.repoguard.agent.external.ExternalHttpResponseProfile;
import com.repoguard.agent.external.OutboundEndpointPolicy;
import com.repoguard.agent.external.OutboundEndpointType;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
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
    private final LlmModelReleaseService modelReleaseService;
    private final AtomicReference<CachedRestClient> cachedRestClient = new AtomicReference<>();

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
        OutboundEndpointPolicy endpointPolicy,
        ObjectProvider<LlmModelReleaseService> modelReleaseServiceProvider
    ) {
        this(
            reviewPolicyProvider,
            restClientBuilder,
            metrics,
            resilience,
            promptBuilder,
            reviewPipeline,
            responseReader,
            responseExtractor,
            endpointPolicy,
            modelReleaseServiceProvider.getIfAvailable(),
            true
        );
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
        this(
            reviewPolicyProvider,
            restClientBuilder,
            metrics,
            resilience,
            promptBuilder,
            reviewPipeline,
            responseReader,
            responseExtractor,
            null,
            null,
            true
        );
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
        LlmModelReleaseService modelReleaseService,
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
        this.modelReleaseService = modelReleaseService;
    }

    @Override
    public ReviewResult review(ReviewTask task, PullRequestDiff diff) {
        return review(task, diff, null);
    }

    @Override
    public ReviewResult review(ReviewTask task, PullRequestDiff diff, ReviewDeadline deadline) {
        ReviewPolicySettings settings = reviewPolicyProvider.getSettings();
        if (modelReleaseService != null) {
            settings = modelReleaseService.route(settings, task);
        }
        return reviewWithSettings(task, diff, deadline, settings);
    }

    /**
     * Executes one evaluation case against the configured provider using the candidate model.
     * Evaluation deliberately bypasses canary routing: a dataset run must compare exactly one
     * immutable model version and must never persist a release assignment or publish side effects.
     */
    public ReviewResult reviewForEvaluation(
        ReviewTask task,
        PullRequestDiff diff,
        ReviewDeadline deadline,
        String provider,
        String model
    ) {
        ReviewPolicySettings configured = reviewPolicyProvider.getSettings();
        if (!configured.enabled() || !configured.readyForLlmReview()) {
            throw new IllegalStateException("LLM 评估运行需要已启用且配置完整的模型服务");
        }
        if (provider == null || provider.isBlank() || model == null || model.isBlank()
            || !provider.trim().equalsIgnoreCase(configured.llmProvider())) {
            throw new IllegalArgumentException("评估版本与当前 LLM 配置不一致");
        }
        ReviewPolicySettings evaluationSettings = new ReviewPolicySettings(
            configured.exists(),
            configured.llmEnabled(),
            configured.llmProvider(),
            model.trim(),
            configured.baseUrl(),
            configured.apiKey(),
            configured.timeoutSeconds(),
            configured.temperature(),
            configured.maxTokens(),
            configured.fallbackToRules(),
            configured.workerConcurrency(),
            configured.chunkFileThreshold(),
            configured.chunkLineThreshold(),
            configured.chunkMaxFiles(),
            configured.chunkMaxLines(),
            configured.inputTokenPricePerMillion(),
            configured.outputTokenPricePerMillion(),
            configured.strategyRelease()
        );
        return reviewWithSettings(task, diff, deadline, evaluationSettings);
    }

    private ReviewResult reviewWithSettings(
        ReviewTask task,
        PullRequestDiff diff,
        ReviewDeadline deadline,
        ReviewPolicySettings settings
    ) {
        long startedAt = System.nanoTime();
        if (deadline != null) {
            deadline.requireRemaining("review_context");
        }
        LlmReviewContext promptContext = promptBuilder.buildContext(diff);
        String promptSummary = promptBuilder.promptSummary(diff, promptContext);
        if (deadline != null) {
            deadline.requireRemaining("review_context");
        }
        return reviewPipeline.execute(
            new ReviewPipelineContext(task, diff, settings, promptSummary, startedAt, this, promptContext, deadline)
        );
    }

    @Override
    public LlmCallResult callLlm(ReviewPolicySettings settings, ReviewTask task, PullRequestDiff diff) {
        return callLlm(settings, task, diff, promptBuilder.buildContext(diff));
    }

    @Override
    public LlmCallResult callLlm(
        ReviewPolicySettings settings,
        ReviewTask task,
        PullRequestDiff diff,
        LlmReviewContext context
    ) {
        return callChat(
            settings,
            promptBuilder.systemPrompt(),
            promptBuilder.buildPrompt(task, diff, context),
            "chat_completions",
            settings.maxTokens()
        );
    }

    @Override
    public boolean supportsHighRiskVerification() {
        return true;
    }

    @Override
    public LlmCallResult verifyHighRisk(
        ReviewPolicySettings settings,
        ReviewTask task,
        PullRequestDiff diff,
        ReviewFindingResult candidate,
        LlmReviewContext context
    ) {
        int maxTokens = Math.min(1_200, Math.max(256, settings.maxTokens() == null ? 1_200 : settings.maxTokens()));
        return callChat(
            settings,
            promptBuilder.verificationSystemPrompt(),
            promptBuilder.buildVerificationPrompt(task, diff, candidate, context),
            "high_risk_verification",
            maxTokens
        );
    }

    private LlmCallResult callChat(
        ReviewPolicySettings settings,
        String systemPrompt,
        String userPrompt,
        String operation,
        Integer maxTokens
    ) {
        long startedAt = System.nanoTime();
        if (endpointPolicy != null) {
            endpointPolicy.validate(OutboundEndpointType.LLM, settings.baseUrl());
        }
        RestClient restClient = restClient(settings);
        String apiKey = settings.apiKey();
        LlmProviderCapability capability = LlmProviderCapabilities.forProvider(settings.llmProvider());
        Map<String, Object> structuredPayload = requestPayload(
            settings,
            systemPrompt,
            userPrompt,
            maxTokens,
            capability,
            operation
        );
        Map<String, Object> legacyPayload = requestPayload(
            settings,
            systemPrompt,
            userPrompt,
            maxTokens,
            new LlmProviderCapability(settings.llmProvider(), LlmStructuredOutputMode.NONE),
            operation
        );
        LlmStructuredOutputStatus outputStatus = capability.supportsStructuredOutput()
            ? LlmStructuredOutputStatus.REQUESTED
            : LlmStructuredOutputStatus.NOT_REQUESTED;

        try {
            JsonNode response;
            try {
                response = executeLlm(operation, () -> executeRequest(
                    restClient,
                    apiKey,
                    structuredPayload
                ));
            } catch (RuntimeException ex) {
                ExternalCallException classified = ExternalCallErrorClassifier.llm(ex);
                if (!capability.supportsStructuredOutput() || !isStructuredOutputUnsupported(classified)) {
                    throw classified;
                }
                outputStatus = LlmStructuredOutputStatus.FALLBACK;
                metrics.llmStructuredOutput(
                    settings.llmProvider(),
                    capability.structuredOutputMode().code(),
                    outputStatus.code(),
                    classified.getCategory()
                );
                response = executeLlm(operation + "_legacy_format", () -> executeRequest(
                    restClient,
                    apiKey,
                    legacyPayload
                ));
            }
            metrics.llmRequestDuration(Duration.ofNanos(System.nanoTime() - startedAt), "success");
            metrics.llmStructuredOutput(
                settings.llmProvider(),
                capability.structuredOutputMode().code(),
                outputStatus.code(),
                "none"
            );
            LlmCallResult extracted = extractLlmCallResult(response);
            return new LlmCallResult(
                extracted.content(),
                extracted.promptTokens(),
                extracted.completionTokens(),
                extracted.totalTokens(),
                outputStatus
            );
        } catch (RuntimeException ex) {
            var classified = ExternalCallErrorClassifier.llm(ex);
            metrics.externalCallFailed(classified);
            metrics.llmRequestDuration(Duration.ofNanos(System.nanoTime() - startedAt), "failed");
            if (capability.supportsStructuredOutput()) {
                metrics.llmStructuredOutput(
                    settings.llmProvider(),
                    capability.structuredOutputMode().code(),
                    LlmStructuredOutputStatus.FAILED.code(),
                    classified.getCategory()
                );
            }
            throw classified;
        }
    }

    private JsonNode executeRequest(
        RestClient restClient,
        String apiKey,
        Map<String, Object> payload
    ) {
        return restClient.post()
            .uri("/chat/completions")
            .header("Authorization", "Bearer " + apiKey.trim())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(payload)
            .exchange((request, clientResponse) -> responseReader.readSuccessfulTree(
                clientResponse,
                "LLM request failed",
                ExternalHttpResponseProfile.LLM
            ));
    }

    private Map<String, Object> requestPayload(
        ReviewPolicySettings settings,
        String systemPrompt,
        String userPrompt,
        Integer maxTokens,
        LlmProviderCapability capability,
        String operation
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", settings.modelName());
        payload.put("temperature", settings.temperature());
        payload.put("max_tokens", maxTokens);
        payload.put("messages", List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", userPrompt)
        ));
        if (capability.supportsStructuredOutput()) {
            boolean verification = "high_risk_verification".equals(operation);
            payload.put(
                "response_format",
                capability.responseFormat(
                    verification
                        ? LlmStructuredOutputSchemas.VERIFICATION_SCHEMA_NAME
                        : LlmStructuredOutputSchemas.REVIEW_SCHEMA_NAME,
                    verification ? LlmStructuredOutputSchemas.verification() : LlmStructuredOutputSchemas.review()
                )
            );
        }
        return payload;
    }

    private boolean isStructuredOutputUnsupported(ExternalCallException exception) {
        if (!"llm_request_invalid".equals(exception.getCategory())) {
            return false;
        }
        String detail = exception.getMessage() == null
            ? ""
            : exception.getMessage().toLowerCase(Locale.ROOT);
        return detail.contains("response_format")
            || detail.contains("json_schema")
            || detail.contains("structured")
            || detail.contains("unsupported")
            || detail.contains("not support")
            || detail.contains("unknown parameter");
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

    private RestClient restClient(ReviewPolicySettings settings) {
        String baseUrl = settings.baseUrl().trim();
        int timeoutSeconds = Math.max(1, settings.timeoutSeconds() == null ? 60 : settings.timeoutSeconds());
        CachedRestClient current = cachedRestClient.get();
        if (current != null && current.matches(baseUrl, timeoutSeconds)) {
            return current.client();
        }
        synchronized (cachedRestClient) {
            current = cachedRestClient.get();
            if (current != null && current.matches(baseUrl, timeoutSeconds)) {
                return current.client();
            }
            RestClient client = restClientBuilder
                .clone()
                .baseUrl(baseUrl)
                .requestFactory(ExternalHttpRequestFactory.sameTimeoutSeconds(timeoutSeconds, 60))
                .build();
            cachedRestClient.set(new CachedRestClient(baseUrl, timeoutSeconds, client));
            return client;
        }
    }

    private record CachedRestClient(String baseUrl, int timeoutSeconds, RestClient client) {

        private boolean matches(String candidateBaseUrl, int candidateTimeoutSeconds) {
            return timeoutSeconds == candidateTimeoutSeconds && baseUrl.equals(candidateBaseUrl);
        }
    }
}
