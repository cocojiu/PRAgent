package com.repoguard.agent.review;

public record RepositorySemanticFile(String path, String content) {

    public RepositorySemanticFile {
        path = path == null ? "" : path;
        content = content == null ? "" : content;
    }
}
