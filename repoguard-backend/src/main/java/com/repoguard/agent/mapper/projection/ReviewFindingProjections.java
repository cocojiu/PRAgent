package com.repoguard.agent.mapper.projection;

public final class ReviewFindingProjections {

    private ReviewFindingProjections() {
    }

    public record RuleHitCount(String ruleId, Long total) {
    }

    public record RuleFeedbackStat(
        Long totalHits,
        Long validCount,
        Long falsePositiveCount,
        Long reviewedCount
    ) {
    }

    public record SeverityCounts(
        Long critical,
        Long high,
        Long medium,
        Long low,
        Long info
    ) {
    }

    public record ReviewTaskDetailSummary(
        Long changedFileTotal,
        Long findingTotal,
        Long missingTestTotal,
        Long critical,
        Long high,
        Long medium,
        Long low,
        Long info
    ) {
    }

    public record GithubCommentPreviewFindingStat(
        Long totalFindings,
        Long commentableFindings,
        Long publishedFindings
    ) {
    }
}
