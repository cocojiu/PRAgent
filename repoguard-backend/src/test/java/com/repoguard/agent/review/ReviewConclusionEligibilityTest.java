package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.dto.FindingSeverityCountsDto;
import com.repoguard.agent.dto.PrRiskProfileDto;
import com.repoguard.agent.dto.PrReviewSummaryDto;
import com.repoguard.agent.dto.ReviewFindingDto;
import com.repoguard.agent.dto.ReviewTaskListItem;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ReviewConclusionEligibilityTest {
    private final ReviewRiskProfileBuilder profiles = new ReviewRiskProfileBuilder();
    private final PrReviewSummaryBuilder summaries = new PrReviewSummaryBuilder();

    @ParameterizedTest
    @MethodSource("unusableConclusions")
    void doesNotRecommendMergeWithoutUsableAssessment(
        String status, String assessment, String humanStatus, String explanation
    ) {
        ReviewTaskListItem task = task(status, assessment, humanStatus, false);
        List<PrRiskProfileDto> riskProfiles = List.of(
            profiles.build(task, List.of(), List.of()),
            profiles.buildSummary(task, FindingSeverityCountsDto.empty(), 0, 0)
        );
        for (PrRiskProfileDto profile : riskProfiles) {
            assertThat(profile.score()).isZero();
            assertThat(profile.level()).isEqualTo("info");
            assertThat(profile.recommendHumanReview()).isFalse();
            assertThat(profile.humanReviewReason()).contains(explanation)
                .doesNotContain("可按常规自动审查流程推进");
            assertThat(profile.signals()).anySatisfy(signal -> assertThat(signal).contains(explanation))
                .doesNotContain("未发现明显放大风险的变更信号");
            for (PrReviewSummaryDto summary : List.of(
                summaries.build(task, List.of(), List.of(), List.of(), profile),
                summaries.build(task, List.of(), List.of(), List.of(), profile,
                    FindingSeverityCountsDto.empty(), 0, 0, 0)
            )) {
                assertThat(summary.recommendMerge()).isFalse();
                assertThat(summary.mergeRecommendation()).contains(explanation);
                assertThat(summary.summary()).doesNotContain("综合风险为提示");
                assertThat(summary.githubCommentBody()).contains(explanation)
                    .doesNotContain("未发现阻塞性风险，可按团队流程合并");
                assertThat(summary.keyRisks()).anySatisfy(risk -> assertThat(risk).contains(explanation));
            }
        }
        assertThat(summaries.build(task, List.of(), List.of(), List.of(), null).recommendMerge()).isFalse();
    }

    static Stream<Arguments> unusableConclusions() {
        return Stream.of(
            Arguments.of("superseded", "superseded", "not_required", "已过期"),
            Arguments.of("SUPERSEDED", "complete", "not_required", "已过期"),
            Arguments.of("completed", "partial", "not_required", "部分评估"),
            Arguments.of("completed", null, "not_required", "不明确"),
            Arguments.of("completed", "", "not_required", "不明确"),
            Arguments.of("completed", "future_status", "not_required", "不明确"),
            Arguments.of(null, "complete", "not_required", "不明确"),
            Arguments.of("UNKNOWN", "complete", "not_required", "不明确"),
            Arguments.of("failed", "complete", "not_required", "失败"),
            Arguments.of("publish_failed", "complete", "not_required", "失败"),
            Arguments.of("execution_timeout", "complete", "not_required", "失败"),
            Arguments.of("completed", "failed", "not_required", "失败"),
            Arguments.of("completed", "superseded", "not_required", "已过期"),
            Arguments.of("queued", "complete", "not_required", "尚未完成"),
            Arguments.of("reviewing", "complete", "not_required", "尚未完成"),
            Arguments.of("requeue_pending", "complete", "not_required", "尚未完成"),
            Arguments.of("pending_human_review", "complete", "pending", "待人工复核"),
            Arguments.of("rejected", "complete", "rejected", "拒绝"),
            Arguments.of("changes_requested", "complete", "changes_requested", "要求修改"),
            Arguments.of("completed", "complete", "rejected", "拒绝"),
            Arguments.of("completed", "complete", "changes_requested", "要求修改")
        );
    }

    @Test
    void keepsCompleteAssessmentAndBlockingRiskBehavior() {
        ReviewTaskListItem task = task(" completed ", " COMPLETE ", "not_required", false);
        PrRiskProfileDto clean = profiles.build(task, List.of(), List.of());
        PrReviewSummaryDto cleanSummary = summaries.build(task, List.of(), List.of(), List.of(), clean);
        assertThat(cleanSummary.recommendMerge()).isTrue();
        assertThat(cleanSummary.mergeRecommendation()).contains("可按团队流程合并");
        assertThat(clean.humanReviewReason()).contains("可按常规自动审查流程推进");

        ReviewFindingDto finding = new ReviewFindingDto("high", "src/Auth.java", 10, "risk", "fix");
        PrReviewSummaryDto blocked = summaries.build(task, List.of(finding), List.of(), List.of(), clean);
        assertThat(blocked.recommendMerge()).isFalse();
        assertThat(blocked.mergeRecommendation()).contains("优先处理高风险发现");
    }

    @Test
    void keepsHumanReviewRequirementAndDoesNotFabricateHighRiskFromMissingEvidence() {
        ReviewTaskListItem task = task("completed", "complete", "pending", true);
        PrRiskProfileDto profile = profiles.build(task, List.of(), List.of());
        assertThat(profile.score()).isZero();
        assertThat(profile.level()).isEqualTo("info");
        assertThat(profile.recommendHumanReview()).isTrue();
        assertThat(summaries.build(task, List.of(), List.of(), List.of(), profile).recommendMerge()).isFalse();
    }

    private ReviewTaskListItem task(String status, String assessment, String humanStatus, boolean humanRequired) {
        return new ReviewTaskListItem(109796L, 134, "existing expired review", "PRAgent", "cocojiu",
            "aea73e8", "main", status, "info", 0, "completed", "github", "github",
            "2026-08-10 16:17:39", "0 分 5 秒", null, null, null, humanRequired, humanStatus,
            null, null, null, assessment);
    }
}
