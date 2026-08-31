package com.repoguard.agent.review;

record LlmCallResult(
    String content,
    Integer promptTokens,
    Integer completionTokens,
    Integer totalTokens,
    LlmStructuredOutputStatus structuredOutputStatus
) {

    LlmCallResult(String content, Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        this(content, promptTokens, completionTokens, totalTokens, LlmStructuredOutputStatus.NOT_REQUESTED);
    }

    LlmCallResult {
        structuredOutputStatus = structuredOutputStatus == null
            ? LlmStructuredOutputStatus.NOT_REQUESTED
            : structuredOutputStatus;
    }

    static LlmCallResult combine(LlmCallResult first, LlmCallResult second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return new LlmCallResult(
            first.content(),
            add(first.promptTokens(), second.promptTokens()),
            add(first.completionTokens(), second.completionTokens()),
            add(first.totalTokens(), second.totalTokens()),
            combineStatus(first.structuredOutputStatus(), second.structuredOutputStatus())
        );
    }

    private static LlmStructuredOutputStatus combineStatus(
        LlmStructuredOutputStatus first,
        LlmStructuredOutputStatus second
    ) {
        if (first == LlmStructuredOutputStatus.FAILED || second == LlmStructuredOutputStatus.FAILED) {
            return LlmStructuredOutputStatus.FAILED;
        }
        if (first == LlmStructuredOutputStatus.FALLBACK || second == LlmStructuredOutputStatus.FALLBACK) {
            return LlmStructuredOutputStatus.FALLBACK;
        }
        if (first == LlmStructuredOutputStatus.REQUESTED || second == LlmStructuredOutputStatus.REQUESTED) {
            return LlmStructuredOutputStatus.REQUESTED;
        }
        return LlmStructuredOutputStatus.NOT_REQUESTED;
    }

    private static Integer add(Integer first, Integer second) {
        if (first == null && second == null) {
            return null;
        }
        return (first == null ? 0 : first) + (second == null ? 0 : second);
    }
}
