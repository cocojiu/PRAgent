package com.repoguard.agent.review;

record LlmCallResult(
    String content,
    Integer promptTokens,
    Integer completionTokens,
    Integer totalTokens,
    LlmStructuredOutputStatus structuredOutputStatus,
    Integer cachedInputTokens,
    String usageSource
) {

    LlmCallResult(String content, Integer promptTokens, Integer completionTokens, Integer totalTokens,
        LlmStructuredOutputStatus structuredOutputStatus) {
        this(content, promptTokens, completionTokens, totalTokens, structuredOutputStatus, null, "CACHE_USAGE_UNKNOWN");
    }

    LlmCallResult(String content, Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        this(content, promptTokens, completionTokens, totalTokens, LlmStructuredOutputStatus.NOT_REQUESTED);
    }

    LlmCallResult {
        usageSource = usageSource == null ? "UNKNOWN" : usageSource;
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
            combineStatus(first.structuredOutputStatus(), second.structuredOutputStatus()),
            add(first.cachedInputTokens(), second.cachedInputTokens()),
            first.usageSource().equals(second.usageSource()) ? first.usageSource()
                : "INVALID_CACHE_DETAILS".equals(first.usageSource()) || "INVALID_CACHE_DETAILS".equals(second.usageSource())
                    ? "INVALID_CACHE_DETAILS" : "MIXED_CACHE_USAGE"
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
        if (first == null || second == null || first < 0 || second < 0) return null;
        long sum = (long) first + second;
        return sum > Integer.MAX_VALUE ? null : (int) sum;
    }
}
