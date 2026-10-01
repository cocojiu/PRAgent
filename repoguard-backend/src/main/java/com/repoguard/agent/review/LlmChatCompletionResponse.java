package com.repoguard.agent.review;

public record LlmChatCompletionResponse(
    String content,
    Integer promptTokens,
    Integer completionTokens,
    Integer totalTokens,
    Integer cachedInputTokens,
    String usageSource
) {
    public LlmChatCompletionResponse(String content, Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        this(content, promptTokens, completionTokens, totalTokens, null, "CACHE_USAGE_UNKNOWN");
    }
}
