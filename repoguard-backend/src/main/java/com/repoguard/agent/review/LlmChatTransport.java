package com.repoguard.agent.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalHttpRequestFactory;
import com.repoguard.agent.external.ExternalHttpJsonResponseReader;
import com.repoguard.agent.external.ExternalHttpResponseProfile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** Owns OpenAI-compatible request construction, HTTP transport and client reuse. */
final class LlmChatTransport {
    private static final int COMPLETE_OUTPUT_TOKEN_ALLOWANCE = 10;
    private final RestClient.Builder restClientBuilder;
    private final ExternalHttpJsonResponseReader responseReader;
    private final LlmChatCompletionResponseExtractor responseExtractor;
    private final LlmUsageBudgetLedger ledger;
    private final AtomicReference<CachedRestClient> cachedRestClient = new AtomicReference<>();

    LlmChatTransport(
        RestClient.Builder restClientBuilder,
        ExternalHttpJsonResponseReader responseReader,
        LlmChatCompletionResponseExtractor responseExtractor
    ) {
        this(restClientBuilder, responseReader, responseExtractor, null);
    }

    LlmChatTransport(RestClient.Builder restClientBuilder, ExternalHttpJsonResponseReader responseReader,
        LlmChatCompletionResponseExtractor responseExtractor, LlmUsageBudgetLedger ledger) {
        this.restClientBuilder = Objects.requireNonNull(restClientBuilder, "restClientBuilder");
        this.responseReader = Objects.requireNonNull(responseReader, "responseReader");
        this.responseExtractor = Objects.requireNonNull(responseExtractor, "responseExtractor");
        this.ledger = ledger;
    }

    JsonNode execute(
        LlmEvaluationBudget budget,
        ReviewPolicySettings settings,
        String systemPrompt,
        String userPrompt,
        Integer maxTokens,
        Map<String, Object> payload,
        LlmProviderCapability capability
    ) {
        return execute(budget, settings, systemPrompt, userPrompt, maxTokens, payload, capability,
            null, java.util.UUID.randomUUID().toString(), "chat_completions");
    }

    JsonNode execute(LlmEvaluationBudget budget, ReviewPolicySettings settings, String systemPrompt,
        String userPrompt, Integer maxTokens, Map<String, Object> payload, LlmProviderCapability capability,
        ReviewTask task, String callKey, String operation) {
        return execute(budget, settings, systemPrompt, userPrompt, maxTokens, payload, capability,
            task, callKey, operation, () -> Long.MAX_VALUE);
    }

    JsonNode execute(LlmEvaluationBudget budget, ReviewPolicySettings settings, String systemPrompt,
        String userPrompt, Integer maxTokens, Map<String, Object> payload, LlmProviderCapability capability,
        ReviewTask task, String callKey, String operation, java.util.function.LongSupplier remaining) {
        int requestedOutputLimit = maxTokens == null ? 1024 : maxTokens;
        int outputLimit = usesCompleteOutputLimit(settings)
            ? Math.addExact(requestedOutputLimit, COMPLETE_OUTPUT_TOKEN_ALLOWANCE) : requestedOutputLimit;
        java.util.function.Supplier<JsonNode> request = () -> {
            if (ledger != null) ledger.requireReleaseAllowed(task);
            // Admission may have waited on a database lock. Check again before the first HTTP byte.
            if (Thread.currentThread().isInterrupted() || remaining.getAsLong() <= 0) {
                throw new LlmUsageBudgetLedger.AdmissionDeniedException("llm_budget_deadline_exhausted");
            }
            return executeRequest(settings, payload, capability);
        };
        java.util.function.Supplier<JsonNode> accounted = ledger == null ? request : () -> ledger.execute(
            task, callKey, operation, settings, systemPrompt, userPrompt, outputLimit, payload, request, responseExtractor::extractUsage);
        return budget == null ? accounted.get() : budget.execute(
            systemPrompt, userPrompt, outputLimit,
            settings.conservativeInputTokenPricePerMillion(),
            settings.outputTokenPricePerMillion(),
            accounted,
            responseExtractor::extractUsage
        );
    }

    Map<String, Object> requestPayload(
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
        payload.put(usesCompleteOutputLimit(settings) ? "max_completion_tokens" : "max_tokens",
            maxTokens == null ? 1024 : maxTokens);
        if (usesCompleteOutputLimit(settings) && (maxTokens == null || maxTokens > 1)) {
            // Stop thinking before it consumes the answer's entire completion budget.
            payload.put("thinking_budget", (maxTokens == null ? 1024 : maxTokens) / 2);
        }
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

    private static boolean usesCompleteOutputLimit(ReviewPolicySettings settings) {
        String provider = settings.llmProvider() == null ? "" : settings.llmProvider().trim().toLowerCase(java.util.Locale.ROOT);
        String model = settings.modelName() == null ? "" : settings.modelName().trim();
        // DashScope documents this limit as reasoning + answer, with up to 10 tokens of drift.
        // Keep it in legacy-format retries too; dropping response_format must not remove the limit.
        return ("dashscope".equals(provider) || "qwen".equals(provider))
            && model.matches("(?i)qwen3\\.7-plus(?:-[0-9]{4}-[0-9]{2}-[0-9]{2})?");
    }

    private JsonNode executeRequest(
        ReviewPolicySettings settings,
        Map<String, Object> payload,
        LlmProviderCapability capability
    ) {
        return restClient(settings, capability).post()
            .uri("/chat/completions")
            .header("Authorization", "Bearer " + settings.apiKey().trim())
            .headers(capability::applyTransportHeaders)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(payload)
            .exchange((request, response) -> responseReader.readSuccessfulTree(
                response,
                "LLM request failed",
                ExternalHttpResponseProfile.LLM
            ));
    }

    private RestClient restClient(ReviewPolicySettings settings, LlmProviderCapability capability) {
        String baseUrl = settings.baseUrl().trim();
        int timeoutSeconds = capability.requestTimeoutSeconds(settings.timeoutSeconds());
        CachedRestClient current = cachedRestClient.get();
        if (current != null && current.matches(baseUrl, timeoutSeconds)) {
            return current.client();
        }
        synchronized (cachedRestClient) {
            current = cachedRestClient.get();
            if (current != null && current.matches(baseUrl, timeoutSeconds)) {
                return current.client();
            }
            RestClient client = restClientBuilder.clone()
                .baseUrl(baseUrl)
                .requestFactory(ExternalHttpRequestFactory.cappedConnectTimeoutSeconds(timeoutSeconds, 60, 10))
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
