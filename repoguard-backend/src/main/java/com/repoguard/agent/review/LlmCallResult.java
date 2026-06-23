package com.repoguard.agent.review;

record LlmCallResult(
    String content,
    Integer promptTokens,
    Integer completionTokens,
    Integer totalTokens
) {
}
