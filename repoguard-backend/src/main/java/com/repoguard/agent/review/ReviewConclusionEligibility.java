package com.repoguard.agent.review;

import com.repoguard.agent.dto.ReviewTaskListItem;
import java.util.Locale;

/** Shared eligibility for risk advice, PR summaries, and their GitHub preview text. */
enum ReviewConclusionEligibility {
    READY(""),
    SUPERSEDED("本次审查已过期，不能据此判断当前提交是否可合并；请按最新提交重新审查。"),
    FAILED("本次审查失败，尚未形成有效评估结论；请排查失败原因并重新审查。"),
    IN_PROGRESS("审查尚未完成，暂不能形成合并结论。"),
    PARTIAL("本次仅完成部分评估，现有发现不足以确认可合并；请完成剩余评估或人工复核。"),
    AWAITING_HUMAN_REVIEW("本次审查仍待人工复核，完成复核前暂不建议合并。"),
    CHANGES_REQUESTED("人工复核要求修改，请完成修改并重新评估后再判断是否合并。"),
    REJECTED("人工复核已拒绝合并，暂不建议合并。"),
    UNAVAILABLE("评估状态不明确，暂不能形成合并结论。");

    private final String explanation;

    ReviewConclusionEligibility(String explanation) {
        this.explanation = explanation;
    }

    boolean usable() {
        return this == READY;
    }

    String explanation() {
        return explanation;
    }

    static ReviewConclusionEligibility forTask(ReviewTaskListItem task) {
        if (task == null) {
            return UNAVAILABLE;
        }
        ReviewTaskStatus status = ReviewTaskStatus.from(task.status());
        String assessment = normalized(task.assessmentStatus());
        if (status == ReviewTaskStatus.SUPERSEDED || AssessmentStatus.SUPERSEDED.name().equals(assessment)) {
            return SUPERSEDED;
        }
        if (status == ReviewTaskStatus.FAILED || status == ReviewTaskStatus.PUBLISH_FAILED
            || status == ReviewTaskStatus.EXECUTION_TIMEOUT || AssessmentStatus.FAILED.name().equals(assessment)) {
            return FAILED;
        }
        if (status == ReviewTaskStatus.QUEUED || status == ReviewTaskStatus.REVIEWING
            || status == ReviewTaskStatus.REQUEUE_PENDING) {
            return IN_PROGRESS;
        }
        String humanStatus = normalized(task.humanReviewStatus());
        if (status == ReviewTaskStatus.REJECTED || "REJECTED".equals(humanStatus)) {
            return REJECTED;
        }
        if (status == ReviewTaskStatus.CHANGES_REQUESTED || "CHANGES_REQUESTED".equals(humanStatus)) {
            return CHANGES_REQUESTED;
        }
        if (status != ReviewTaskStatus.COMPLETED && status != ReviewTaskStatus.PENDING_HUMAN_REVIEW
            && status != ReviewTaskStatus.APPROVED) {
            return UNAVAILABLE;
        }
        if (AssessmentStatus.PARTIAL.name().equals(assessment)) {
            return PARTIAL;
        }
        if (!AssessmentStatus.COMPLETE.name().equals(assessment)) {
            return UNAVAILABLE;
        }
        return status == ReviewTaskStatus.PENDING_HUMAN_REVIEW ? AWAITING_HUMAN_REVIEW : READY;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
