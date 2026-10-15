package com.repoguard.agent.review;

import java.math.BigDecimal;
import java.util.Objects;

/** Immutable runtime policy, grouped by the decisions that change together. */
public record ReviewPolicySettings(boolean exists, Boolean llmEnabled, Connection connection,
    Generation generation, Boolean fallbackToRules, ChunkPolicy chunkPolicy, Pricing pricing,
    ReviewStrategyRelease strategyRelease) {

    public record Connection(String provider, String model, String baseUrl, String apiKey, Integer timeoutSeconds) {
        public Connection withModel(String model) { return new Connection(provider, model, baseUrl, apiKey, timeoutSeconds); }
        @Override public String toString() {
            return "Connection[provider=" + provider + ", model=" + model + ", credentials=redacted]";
        }
    }
    public record Generation(BigDecimal temperature, Integer maxTokens) { }
    public record ChunkPolicy(Integer workerConcurrency, Integer fileThreshold, Integer lineThreshold,
        Integer maxFiles, Integer maxLines) { }
    public record Pricing(BigDecimal input, BigDecimal output, BigDecimal cachedInput) {
        /** Reservations cannot assume either a cache hit or a discount. */
        public BigDecimal conservativeInput() {
            return input == null ? null : cachedInput == null ? input : input.max(cachedInput);
        }
    }

    public ReviewPolicySettings {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(chunkPolicy, "chunkPolicy");
        Objects.requireNonNull(pricing, "pricing");
        strategyRelease = strategyRelease == null ? ReviewStrategyRelease.observeDefaults() : strategyRelease;
    }

    /** Compatibility at the configuration boundary; runtime changes use named copies below. */
    public ReviewPolicySettings(boolean exists, Boolean enabled, String provider, String model,
        String baseUrl, String apiKey, Integer timeout, BigDecimal temperature, Integer maxTokens,
        Boolean fallback, Integer workers, Integer fileThreshold, Integer lineThreshold, Integer maxFiles,
        Integer maxLines, BigDecimal input, BigDecimal output, ReviewStrategyRelease strategy, BigDecimal cachedInput) {
        this(exists, enabled, new Connection(provider, model, baseUrl, apiKey, timeout),
            new Generation(temperature, maxTokens), fallback,
            new ChunkPolicy(workers, fileThreshold, lineThreshold, maxFiles, maxLines),
            new Pricing(input, output, cachedInput), strategy);
    }
    public ReviewPolicySettings(boolean exists, Boolean enabled, String provider, String model,
        String baseUrl, String apiKey, Integer timeout, BigDecimal temperature, Integer maxTokens,
        Boolean fallback, Integer workers, Integer fileThreshold, Integer lineThreshold, Integer maxFiles,
        Integer maxLines, BigDecimal input, BigDecimal output, ReviewStrategyRelease strategy) {
        this(exists, enabled, provider, model, baseUrl, apiKey, timeout, temperature, maxTokens, fallback,
            workers, fileThreshold, lineThreshold, maxFiles, maxLines, input, output, strategy, null);
    }
    public ReviewPolicySettings(boolean exists, Boolean enabled, String provider, String model,
        String baseUrl, String apiKey, Integer timeout, BigDecimal temperature, Integer maxTokens,
        Boolean fallback, Integer workers, Integer fileThreshold, Integer lineThreshold, Integer maxFiles,
        Integer maxLines, BigDecimal input, BigDecimal output) {
        this(exists, enabled, provider, model, baseUrl, apiKey, timeout, temperature, maxTokens, fallback,
            workers, fileThreshold, lineThreshold, maxFiles, maxLines, input, output,
            ReviewStrategyRelease.legacyRuntimeDefaults(), null);
    }
    public static ReviewPolicySettings empty() {
        return new ReviewPolicySettings(false, false, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, ReviewStrategyRelease.observeDefaults());
    }
    public ReviewPolicySettings withModel(String model) {
        return new ReviewPolicySettings(exists, llmEnabled, connection.withModel(model), generation,
            fallbackToRules, chunkPolicy, pricing, strategyRelease);
    }
    public ReviewPolicySettings withExecution(Boolean enabled, Integer maxTokens, Boolean fallback) {
        return new ReviewPolicySettings(exists, enabled, connection, new Generation(temperature(), maxTokens),
            fallback, chunkPolicy, pricing, strategyRelease);
    }
    public ReviewPolicySettings withoutLlm() { return withExecution(false, maxTokens(), true); }
    public String llmProvider() { return connection.provider(); }
    public String modelName() { return connection.model(); }
    public String baseUrl() { return connection.baseUrl(); }
    public String apiKey() { return connection.apiKey(); }
    public Integer timeoutSeconds() { return connection.timeoutSeconds(); }
    public BigDecimal temperature() { return generation.temperature(); }
    public Integer maxTokens() { return generation.maxTokens(); }
    public Integer workerConcurrency() { return chunkPolicy.workerConcurrency(); }
    public Integer chunkFileThreshold() { return chunkPolicy.fileThreshold(); }
    public Integer chunkLineThreshold() { return chunkPolicy.lineThreshold(); }
    public Integer chunkMaxFiles() { return chunkPolicy.maxFiles(); }
    public Integer chunkMaxLines() { return chunkPolicy.maxLines(); }
    public BigDecimal inputTokenPricePerMillion() { return pricing.input(); }
    public BigDecimal outputTokenPricePerMillion() { return pricing.output(); }
    public BigDecimal cachedInputTokenPricePerMillion() { return pricing.cachedInput(); }
    public BigDecimal conservativeInputTokenPricePerMillion() { return pricing.conservativeInput(); }
    public boolean enabled() { return Boolean.TRUE.equals(llmEnabled); }
    public boolean readyForLlmReview() {
        return hasText(baseUrl()) && hasText(modelName()) && hasText(apiKey()) && !"mock".equalsIgnoreCase(llmProvider());
    }
    private boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }
}
