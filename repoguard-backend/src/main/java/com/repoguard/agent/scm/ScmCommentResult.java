package com.repoguard.agent.scm;

public record ScmCommentResult(
    String provider,
    Long findingId,
    Boolean success,
    String status,
    String message,
    String url,
    Long remoteId
) {
}
