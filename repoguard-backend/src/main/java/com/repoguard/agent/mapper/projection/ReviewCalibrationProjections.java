package com.repoguard.agent.mapper.projection;

import java.time.LocalDateTime;

public final class ReviewCalibrationProjections {

    private ReviewCalibrationProjections() {
    }

    public record Summary(
        Long totalFindings,
        Long labeledCount,
        Long confirmedValidCount,
        Long falsePositiveCount,
        Long pendingCount,
        Long anchoredCount,
        Long duplicateCount
    ) {
    }

    public record Sample(
        Long findingId,
        Long taskId,
        Integer prNumber,
        String title,
        String repository,
        String organization,
        String commitSha,
        String prUrl,
        LocalDateTime taskCreatedAt,
        String source,
        String ruleId,
        String severity,
        String confidence,
        String filePath,
        Integer lineNumber,
        String message,
        String evidence,
        String impact,
        String recommendation,
        String preconditions,
        String issueType,
        String verificationStatus,
        Boolean blockingCandidate,
        String enforcementMode,
        String feedbackStatus,
        String detectorVersion,
        Long ruleConfigVersion,
        Long policyVersion,
        String promptVersion,
        String contextVersion,
        String schemaVersion,
        String verifierVersion,
        String aggregationVersion
    ) {
    }
}
