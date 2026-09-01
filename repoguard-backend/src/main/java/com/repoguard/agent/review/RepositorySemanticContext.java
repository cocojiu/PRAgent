package com.repoguard.agent.review;

import java.util.List;

record RepositorySemanticContext(
    String defaultBranch,
    List<LlmContextSlice> slices,
    List<LlmReviewContext.ContextLimitation> limitations,
    boolean truncated,
    String summary
) {

    RepositorySemanticContext {
        defaultBranch = defaultBranch == null ? "" : defaultBranch;
        slices = slices == null ? List.of() : List.copyOf(slices);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        summary = summary == null ? "" : summary;
    }

    static RepositorySemanticContext empty(String reason) {
        return new RepositorySemanticContext("", List.of(), List.of(), false, reason);
    }
}
