package com.repoguard.agent.review;

public record RepositorySemanticLimitation(String filePath, String status, String reason) {

    public RepositorySemanticLimitation {
        filePath = filePath == null ? "[repository]" : filePath;
        status = status == null ? "UNAVAILABLE" : status;
        reason = reason == null ? "unknown" : reason;
    }
}
