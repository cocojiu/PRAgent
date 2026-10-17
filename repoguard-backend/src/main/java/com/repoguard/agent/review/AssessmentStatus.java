package com.repoguard.agent.review;

import com.repoguard.agent.dto.ReviewTaskListItem;
import java.util.Locale;
import java.util.Optional;

public enum AssessmentStatus {
    COMPLETE,
    PARTIAL,
    FAILED,
    SUPERSEDED;

    public static AssessmentStatus forCompletedReview(ReviewResult result) {
        if (result == null) {
            return FAILED;
        }
        String parseStatus = normalize(result.llmParseStatus());
        String llmStatus = normalize(result.llmStatus());
        if ("PARTIAL_FALLBACK".equals(parseStatus)
            || "FALLBACK".equals(parseStatus)
            || "FALLBACK".equals(llmStatus)) {
            return PARTIAL;
        }
        return COMPLETE;
    }

    /** Empty only when the current assessment may support a merge conclusion. */
    public static Optional<String> conclusionBlockReason(ReviewTaskListItem task) {
        String unavailable = "评估状态不明确，暂不能形成合并结论。";
        if (task == null) {
            return Optional.of(unavailable);
        }
        ReviewTaskStatus status = ReviewTaskStatus.from(task.status());
        String assessment = normalize(task.assessmentStatus());
        if (status == ReviewTaskStatus.SUPERSEDED || SUPERSEDED.name().equals(assessment)) {
            return Optional.of("本次审查已过期，不能据此判断当前提交是否可合并；请按最新提交重新审查。");
        }
        if (status == ReviewTaskStatus.FAILED || status == ReviewTaskStatus.PUBLISH_FAILED
            || status == ReviewTaskStatus.EXECUTION_TIMEOUT || FAILED.name().equals(assessment)) {
            return Optional.of("本次审查失败，尚未形成有效评估结论；请排查失败原因并重新审查。");
        }
        if (status == ReviewTaskStatus.QUEUED || status == ReviewTaskStatus.REVIEWING
            || status == ReviewTaskStatus.REQUEUE_PENDING) {
            return Optional.of("审查尚未完成，暂不能形成合并结论。");
        }
        String humanStatus = normalize(task.humanReviewStatus());
        if (status == ReviewTaskStatus.REJECTED || "REJECTED".equals(humanStatus)) {
            return Optional.of("人工复核已拒绝合并，暂不建议合并。");
        }
        if (status == ReviewTaskStatus.CHANGES_REQUESTED || "CHANGES_REQUESTED".equals(humanStatus)) {
            return Optional.of("人工复核要求修改，请完成修改并重新评估后再判断是否合并。");
        }
        if (status != ReviewTaskStatus.COMPLETED && status != ReviewTaskStatus.PENDING_HUMAN_REVIEW
            && status != ReviewTaskStatus.APPROVED) {
            return Optional.of(unavailable);
        }
        if (PARTIAL.name().equals(assessment)) {
            return Optional.of("本次仅完成部分评估，现有发现不足以确认可合并；请完成剩余评估或人工复核。");
        }
        if (!COMPLETE.name().equals(assessment)) {
            return Optional.of(unavailable);
        }
        return status == ReviewTaskStatus.PENDING_HUMAN_REVIEW
            ? Optional.of("本次审查仍待人工复核，完成复核前暂不建议合并。") : Optional.empty();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
