package com.repoguard.agent.review;

public record PullRequestChangedFile(
    String filename,
    String status,
    Integer additions,
    Integer deletions,
    String patch,
    ChangedFileContext context
) {

    public PullRequestChangedFile {
        context = context == null ? ChangedFileContext.notRequested(filename) : context;
    }

    public PullRequestChangedFile(
        String filename,
        String status,
        Integer additions,
        Integer deletions,
        String patch
    ) {
        this(filename, status, additions, deletions, patch, ChangedFileContext.notRequested(filename));
    }

    public PullRequestChangedFile withContext(ChangedFileContext changedFileContext) {
        return new PullRequestChangedFile(filename, status, additions, deletions, patch, changedFileContext);
    }
}
