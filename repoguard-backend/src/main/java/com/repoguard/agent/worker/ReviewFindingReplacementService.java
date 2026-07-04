package com.repoguard.agent.worker;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.review.ReviewFindingResult;
import com.repoguard.agent.review.ReviewResult;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class ReviewFindingReplacementService {

    private final ReviewFindingMapper reviewFindingMapper;
    private final ReviewFindingDeduplicator findingDeduplicator;

    ReviewFindingReplacementService(
        ReviewFindingMapper reviewFindingMapper,
        ReviewFindingDeduplicator findingDeduplicator
    ) {
        this.reviewFindingMapper = reviewFindingMapper;
        this.findingDeduplicator = Objects.requireNonNull(findingDeduplicator, "findingDeduplicator");
    }

    int replace(Long taskId, ReviewResult reviewResult) {
        reviewFindingMapper.delete(new LambdaQueryWrapper<ReviewFinding>().eq(ReviewFinding::getTaskId, taskId));
        List<ReviewFindingResult> findings = findingDeduplicator.deduplicate(reviewResult.findings());
        for (ReviewFindingResult findingResult : findings) {
            ReviewFinding finding = new ReviewFinding();
            finding.setTaskId(taskId);
            finding.setCategory("FINDING");
            finding.setSeverity(findingResult.severity());
            finding.setSource(findingResult.source());
            finding.setRuleId(findingResult.ruleId());
            finding.setFilePath(findingResult.filePath());
            finding.setLineNumber(findingResult.lineNumber());
            finding.setMessage(findingResult.message());
            finding.setRecommendation(findingResult.recommendation());
            finding.setConfidence(findingResult.confidence());
            finding.setEvidence(findingResult.evidence());
            finding.setImpact(findingResult.impact());
            finding.setFixExample(findingResult.fixExample());
            finding.setIsBlocking(findingResult.isBlocking());
            finding.setReviewDimension(findingResult.reviewDimension());
            reviewFindingMapper.insert(finding);
        }
        return findings.size();
    }
}
