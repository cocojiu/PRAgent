package com.repoguard.agent.review;

import java.util.List;

public record RepositorySemanticSnapshot(
    String defaultBranch,
    List<RepositorySemanticFile> files,
    List<RepositorySemanticLimitation> limitations,
    boolean truncated,
    String summary
) {

    public RepositorySemanticSnapshot {
        defaultBranch = defaultBranch == null ? "" : defaultBranch;
        files = files == null ? List.of() : List.copyOf(files);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        summary = summary == null ? "" : summary;
    }

    public static RepositorySemanticSnapshot empty(String summary) {
        return new RepositorySemanticSnapshot("", List.of(), List.of(), false, summary);
    }
}
