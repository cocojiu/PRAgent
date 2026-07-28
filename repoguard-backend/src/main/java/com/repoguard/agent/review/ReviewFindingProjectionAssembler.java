package com.repoguard.agent.review;

import com.repoguard.agent.dto.FindingSeverityCountsDto;
import com.repoguard.agent.dto.GithubCommentPreviewFindingStat;
import com.repoguard.agent.dto.ReviewRuleFeedbackStat;
import com.repoguard.agent.dto.ReviewRuleHitCount;
import com.repoguard.agent.mapper.projection.ReviewFindingProjections;
import java.util.List;

public final class ReviewFindingProjectionAssembler {

    private ReviewFindingProjectionAssembler() {
    }

    public static List<ReviewRuleHitCount> toRuleHitDtos(List<ReviewFindingProjections.RuleHitCount> sources) {
        if (sources == null) {
            return null;
        }
        return sources.stream().map(source -> {
            ReviewRuleHitCount target = new ReviewRuleHitCount();
            target.setRuleId(source.ruleId());
            target.setTotal(source.total());
            return target;
        }).toList();
    }

    public static ReviewRuleFeedbackStat toDto(ReviewFindingProjections.RuleFeedbackStat source) {
        if (source == null) {
            return null;
        }
        ReviewRuleFeedbackStat target = new ReviewRuleFeedbackStat();
        target.setTotalHits(source.totalHits());
        target.setValidCount(source.validCount());
        target.setFalsePositiveCount(source.falsePositiveCount());
        target.setReviewedCount(source.reviewedCount());
        return target;
    }

    public static FindingSeverityCountsDto toDto(ReviewFindingProjections.SeverityCounts source) {
        return source == null
            ? null
            : new FindingSeverityCountsDto(
                source.critical(),
                source.high(),
                source.medium(),
                source.low(),
                source.info()
            );
    }

    public static GithubCommentPreviewFindingStat toDto(
        ReviewFindingProjections.GithubCommentPreviewFindingStat source
    ) {
        if (source == null) {
            return null;
        }
        GithubCommentPreviewFindingStat target = new GithubCommentPreviewFindingStat();
        target.setTotalFindings(source.totalFindings());
        target.setCommentableFindings(source.commentableFindings());
        target.setPublishedFindings(source.publishedFindings());
        return target;
    }
}
