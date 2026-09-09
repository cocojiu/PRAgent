package com.repoguard.agent.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.repoguard.agent.review.quality.LlmModelReleaseService;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalCallErrorClassifier;
import com.repoguard.agent.external.ExternalCallException;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.external.ExternalHttpJsonResponseReader;
import com.repoguard.agent.external.OutboundEndpointPolicy;
import com.repoguard.agent.external.OutboundEndpointType;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
@Service
public class LlmPullRequestReviewer implements PullRequestReviewer, LlmReviewCaller {

    private final ReviewPolicyProvider reviewPolicyProvider;
    private final RepoGuardMetrics metrics;
    private final ExternalCallResilience resilience;
    private final LlmReviewPromptBuilder promptBuilder;
    private final LlmReviewPipeline reviewPipeline;
    private final LlmChatCompletionResponseExtractor responseExtractor;
    private final LlmChatTransport chatTransport;
    private final OutboundEndpointPolicy endpointPolicy;
    private final LlmModelReleaseService modelReleaseService;
    private final RepositoryPolicyRuntime repositoryPolicyRuntime;
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
        ObjectProvider<LlmModelReleaseService> modelReleaseServiceProvider,
        ObjectProvider<RepositoryPolicyRuntime> repositoryPolicyRuntimeProvider
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
            repositoryPolicyRuntimeProvider.getIfAvailable(),
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
        RepositoryPolicyRuntime repositoryPolicyRuntime,
        boolean ignored
    ) {
        this.reviewPolicyProvider = Objects.requireNonNull(reviewPolicyProvider, "reviewPolicyProvider");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.resilience = Objects.requireNonNull(resilience, "resilience");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.reviewPipeline = Objects.requireNonNull(reviewPipeline, "reviewPipeline");
        this.responseExtractor = Objects.requireNonNull(responseExtractor, "responseExtractor");
        this.chatTransport = new LlmChatTransport(restClientBuilder, responseReader, responseExtractor);
        this.endpointPolicy = endpointPolicy;
        this.modelReleaseService = modelReleaseService;
        this.repositoryPolicyRuntime = repositoryPolicyRuntime;
    }
    @Override
    public ReviewResult review(ReviewTask task, PullRequestDiff diff) {
        return review(task, diff, null);
    }
    @Override
    public ReviewResult review(ReviewTask task, PullRequestDiff diff, ReviewDeadline deadline) {
        ReviewPolicySettings settings = reviewPolicyProvider.getSettings();
        if (repositoryPolicyRuntime != null) {
            settings = repositoryPolicyRuntime.applyLlmSettings(task, settings);
        }
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
        return reviewWithSettings(task, diff, deadline, evaluationSettings(task, provider, model));
    }

    public ReviewResult reviewForEvaluation(
        ReviewTask task,
        PullRequestDiff diff,
        ReviewDeadline deadline,
        String provider,
        String model,
        LlmEvaluationBudget budget
    ) {
        ReviewPolicySettings settings = evaluationSettings(task, provider, model);
        return reviewWithSettings(
            task,
            diff,
            deadline,
            settings,
            new LlmEvaluationReviewCaller(this, promptBuilder, budget)
        );
    }

    private ReviewPolicySettings evaluationSettings(ReviewTask task, String provider, String model) {
        ReviewPolicySettings configured = reviewPolicyProvider.getSettings();
        if (repositoryPolicyRuntime != null) {
            configured = repositoryPolicyRuntime.applyLlmSettings(task, configured);
        }
        return LlmEvaluationReviewCaller.settings(configured, provider, model);
    }

    private ReviewResult reviewWithSettings(
        ReviewTask task,
        PullRequestDiff diff,
        ReviewDeadline deadline,
        ReviewPolicySettings settings
    ) {
        return reviewWithSettings(task, diff, deadline, settings, this);
    }

    private ReviewResult reviewWithSettings(
        ReviewTask task,
        PullRequestDiff diff,
        ReviewDeadline deadline,
        ReviewPolicySettings settings,
        LlmReviewCaller caller
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
            new ReviewPipelineContext(task, diff, settings, promptSummary, startedAt, caller, promptContext, deadline)
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
        return callChat(settings, systemPrompt, userPrompt, operation, maxTokens, null);
    }

    LlmCallResult callChat(
        ReviewPolicySettings settings,
        String systemPrompt,
        String userPrompt,
        String operation,
        Integer maxTokens,
        LlmEvaluationBudget evaluationBudget
    ) {
        long startedAt = System.nanoTime();
        if (endpointPolicy != null) {
            endpointPolicy.validate(OutboundEndpointType.LLM, settings.baseUrl());
        }
        LlmProviderCapability capability = LlmProviderCapabilities.forProvider(settings.llmProvider());
        Map<String, Object> structuredPayload = chatTransport.requestPayload(
            settings,
            systemPrompt,
            userPrompt,
            maxTokens,
            capability,
            operation
        );
        Map<String, Object> legacyPayload = chatTransport.requestPayload(
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
                response = executeLlm(operation, () -> chatTransport.execute(
                    evaluationBudget,
                    settings,
                    systemPrompt,
                    userPrompt,
                    maxTokens,
                    structuredPayload,
                    capability
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
                response = executeLlm(operation + "_legacy_format", () -> chatTransport.execute(
                    evaluationBudget,
                    settings,
                    systemPrompt,
                    userPrompt,
                    maxTokens,
                    legacyPayload,
                    capability
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
            if (ex instanceof LlmEvaluationBudget.BudgetExceededException) {
                throw ex;
            }
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

}
