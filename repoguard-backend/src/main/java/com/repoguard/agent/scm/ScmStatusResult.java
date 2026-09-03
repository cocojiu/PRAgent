package com.repoguard.agent.scm;

public record ScmStatusResult(
    String provider,
    Boolean success,
    String state,
    String message,
    String url
) {
}
