package com.repoguard.agent.review;

import com.repoguard.agent.config.ReviewHumanReviewProperties;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class HumanReviewPolicyEvaluator {

    private final RiskLevelRanker riskLevelRanker;
    private final ReviewHumanReviewProperties properties;

    public HumanReviewPolicyEvaluator(
        RiskLevelRanker riskLevelRanker,
        ReviewHumanReviewProperties properties
    ) {
        this.riskLevelRanker = Objects.requireNonNull(riskLevelRanker, "riskLevelRanker");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public boolean requiresHumanReview(ReviewResult reviewResult) {
        List<ReviewFindingResult> findings = reviewResult == null || reviewResult.findings() == null
            ? List.of()
            : reviewResult.findings();
        return requiresHumanReview(findings);
    }

    public boolean requiresHumanReview(List<ReviewFindingResult> findings) {
        return findings != null && findings.stream().anyMatch(this::requiresHumanReview);
    }

    public boolean requiresHumanReview(String riskLevel) {
        return riskLevelRanker.atLeast(riskLevel, properties.getMinimumSeverity());
    }

    private boolean requiresHumanReview(ReviewFindingResult finding) {
        if (finding == null) {
            return false;
        }
        if (properties.isRequireBlocking() && !finding.isBlocking()) {
            return false;
        }
        return riskLevelRanker.atLeast(finding.severity(), properties.getMinimumSeverity())
            && confidenceRank(finding.confidence()) >= confidenceRank(properties.getMinimumConfidence());
    }

    private int confidenceRank(String value) {
        if ("HIGH".equalsIgnoreCase(value)) {
            return 3;
        }
        if ("MEDIUM".equalsIgnoreCase(value)) {
            return 2;
        }
        if ("LOW".equalsIgnoreCase(value)) {
            return 1;
        }
        return 0;
    }
}
