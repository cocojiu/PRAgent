package com.repoguard.agent.dto;

public class GithubCommentPreviewFindingStat {

    private Long totalFindings;
    private Long commentableFindings;
    private Long publishedFindings;

    public Long getTotalFindings() {
        return totalFindings;
    }

    public void setTotalFindings(Long totalFindings) {
        this.totalFindings = totalFindings;
    }

    public Long getCommentableFindings() {
        return commentableFindings;
    }

    public void setCommentableFindings(Long commentableFindings) {
        this.commentableFindings = commentableFindings;
    }

    public Long getPublishedFindings() {
        return publishedFindings;
    }

    public void setPublishedFindings(Long publishedFindings) {
        this.publishedFindings = publishedFindings;
    }

    public long totalFindingsOrZero() {
        return totalFindings == null ? 0L : totalFindings;
    }

    public long commentableFindingsOrZero() {
        return commentableFindings == null ? 0L : commentableFindings;
    }

    public long publishedFindingsOrZero() {
        return publishedFindings == null ? 0L : publishedFindings;
    }
}
