package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.review.ReviewFindingResult;
import org.springframework.stereotype.Component;

@Component
class ReviewFindingEntityMapper {

    ReviewFinding toEntity(Long taskId, ReviewFindingResult findingResult) {
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
        return finding;
    }
}
