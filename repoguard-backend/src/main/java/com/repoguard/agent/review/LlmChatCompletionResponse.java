package com.repoguard.agent.review;

public record LlmChatCompletionResponse(
    String content,
    Integer promptTokens,
    Integer completionTokens,
    Integer totalTokens
) {
}
