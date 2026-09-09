package com.repoguard.agent.review;

import com.fasterxml.jackson.databind.JsonNode;
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
    private final RestClient.Builder restClientBuilder;
    private final ExternalHttpJsonResponseReader responseReader;
    private final LlmChatCompletionResponseExtractor responseExtractor;
    private final AtomicReference<CachedRestClient> cachedRestClient = new AtomicReference<>();

    LlmChatTransport(
        RestClient.Builder restClientBuilder,
        ExternalHttpJsonResponseReader responseReader,
        LlmChatCompletionResponseExtractor responseExtractor
    ) {
        this.restClientBuilder = Objects.requireNonNull(restClientBuilder, "restClientBuilder");
        this.responseReader = Objects.requireNonNull(responseReader, "responseReader");
        this.responseExtractor = Objects.requireNonNull(responseExtractor, "responseExtractor");
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
        if (budget == null) {
            return executeRequest(settings, payload, capability);
        }
        return budget.execute(
            systemPrompt,
            userPrompt,
            maxTokens,
            settings.inputTokenPricePerMillion(),
            settings.outputTokenPricePerMillion(),
            () -> executeRequest(settings, payload, capability),
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
