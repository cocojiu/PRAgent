package com.repoguard.agent.review;

record LlmCallResult(
    String content,
    Integer promptTokens,
    Integer completionTokens,
    Integer totalTokens
) {

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
            add(first.totalTokens(), second.totalTokens())
        );
    }

    private static Integer add(Integer first, Integer second) {
        if (first == null && second == null) {
            return null;
        }
        return (first == null ? 0 : first) + (second == null ? 0 : second);
    }
}
