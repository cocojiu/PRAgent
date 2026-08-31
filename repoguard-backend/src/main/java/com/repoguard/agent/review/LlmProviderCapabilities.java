package com.repoguard.agent.review;

import java.util.Locale;

/**
 * Small, explicit capability registry for the OpenAI-compatible providers supported by RepoGuard.
 * Capability is keyed by provider rather than guessed from a model name so an unknown endpoint
 * never receives a request parameter it may reject.
 */
public final class LlmProviderCapabilities {

    private LlmProviderCapabilities() {
    }

    public static LlmProviderCapability forProvider(String provider) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (normalized) {
            case "openai", "azure-openai", "azureopenai", "openai-compatible" ->
                new LlmProviderCapability(normalized, LlmStructuredOutputMode.JSON_SCHEMA);
            case "dashscope", "qwen", "deepseek", "moonshot" ->
                new LlmProviderCapability(normalized, LlmStructuredOutputMode.JSON_OBJECT);
            default -> new LlmProviderCapability(normalized, LlmStructuredOutputMode.NONE);
        };
    }
}
