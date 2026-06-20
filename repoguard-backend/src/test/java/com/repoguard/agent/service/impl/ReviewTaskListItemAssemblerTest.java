package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.service.impl.ReviewFailureSummaryResolver.ReviewFailureSummary;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ReviewTaskListItemAssemblerTest {

    private final ReviewTaskListItemAssembler assembler = new ReviewTaskListItemAssembler();

    @Test
    void assemblesListItemAndNormalizesDisplayFields() {
        ReviewTask task = baseTask();
        task.setStatus("COMPLETED");
        task.setRiskLevel("HIGH");
        task.setLlmStatus("FALLBACK");
        task.setSource("GITHUB_PR_PICKER");
        task.setTriggerSource("MANUAL_INPUT");
        task.setDurationSeconds(125);
        task.setHumanReviewRequired(true);
        task.setHumanReviewStatus("PENDING");
        task.setHumanReviewNote("verify manually");
        task.setHumanReviewBy("reviewer");
        task.setHumanReviewedAt(LocalDateTime.of(2026, 6, 19, 10, 8));
        ReviewFailureSummary failureSummary = new ReviewFailureSummary(
            "github_timeout",
            "GitHub API 响应超时",
            "请稍后重试"
        );

        var result = assembler.assemble(task, failureSummary);

        assertThat(result.id()).isEqualTo(521L);
        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.riskLevel()).isEqualTo("high");
        assertThat(result.llmStatus()).isEqualTo("fallback");
        assertThat(result.source()).isEqualTo("github_pr_picker");
        assertThat(result.triggerSource()).isEqualTo("manual_input");
        assertThat(result.createdAt()).isEqualTo("2026-06-19 10:00:00");
        assertThat(result.duration()).isEqualTo("2 分 5 秒");
        assertThat(result.failureCategory()).isEqualTo("github_timeout");
        assertThat(result.humanReviewRequired()).isTrue();
        assertThat(result.humanReviewStatus()).isEqualTo("pending");
        assertThat(result.humanReviewedAt()).isEqualTo("2026-06-19 10:08:00");
    }

    @Test
    void appliesDefaultsForBlankSourceDurationAndHumanReview() {
        ReviewTask task = baseTask();
        task.setStatus(null);
        task.setRiskLevel(null);
        task.setLlmStatus(null);
        task.setSource(" ");
        task.setTriggerSource(null);
        task.setDurationSeconds(null);
        task.setHumanReviewRequired(false);

        var result = assembler.assemble(task, new ReviewFailureSummary(null, null, null));

        assertThat(result.status()).isNull();
        assertThat(result.riskLevel()).isNull();
        assertThat(result.llmStatus()).isNull();
        assertThat(result.source()).isEqualTo("manual_input");
        assertThat(result.triggerSource()).isEqualTo("manual_input");
        assertThat(result.duration()).isEqualTo("0 分 0 秒");
        assertThat(result.humanReviewRequired()).isFalse();
        assertThat(result.humanReviewStatus()).isEqualTo("not_required");
    }

    private ReviewTask baseTask() {
        ReviewTask task = new ReviewTask();
        task.setId(521L);
        task.setPrNumber(42);
        task.setTitle("Review task");
        task.setRepository("Hello-World");
        task.setOrganization("octocat");
        task.setCommitSha("abc123");
        task.setBranchName("main");
        task.setMqRetries(2);
        task.setCreatedAt(LocalDateTime.of(2026, 6, 19, 10, 0));
        return task;
    }
}
