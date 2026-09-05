package com.repoguard.agent.review;

import java.util.Locale;
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
