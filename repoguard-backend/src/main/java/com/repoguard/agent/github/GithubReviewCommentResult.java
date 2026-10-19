package com.repoguard.agent.github;

public record GithubReviewCommentResult(
    Long findingId,
    String path,
    Integer line,
    String targetType,
    Boolean success,
    String status,
    String message,
    String url,
    Long commentId
) {
    public static GithubReviewCommentResult failed(
        GithubReviewCommentDraft draft, String message, RuntimeException failure
    ) {
        return new GithubReviewCommentResult(
            draft.findingId(), draft.path(), draft.line(), draft.targetType(), false,
            GithubCommentPublicationStatus.failureCode(failure), message, null, null
        );
    }
}
