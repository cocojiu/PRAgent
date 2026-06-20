package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.dto.ReviewTaskListItem;
import com.repoguard.agent.dto.ReviewTimelineItem;
import com.repoguard.agent.entity.ReviewTask;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ReviewTaskStatusAssemblerTest {

    private final ReviewTaskStatusAssembler assembler = new ReviewTaskStatusAssembler();

    @Test
    void assemblesStatusResponseUsingFinishedAtAsUpdatedAt() {
        ReviewTask task = baseTask();
        task.setStartedAt(LocalDateTime.of(2026, 6, 19, 10, 1));
        task.setFinishedAt(LocalDateTime.of(2026, 6, 19, 10, 5));
        ReviewTimelineItem latestTimeline = new ReviewTimelineItem("Review completed", "10:05:00", "done");

        var result = assembler.assemble(task, baseItem(), latestTimeline);

        assertThat(result.id()).isEqualTo(521L);
        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.updatedAt()).isEqualTo("2026-06-19 10:05:00");
        assertThat(result.latestTimeline()).isSameAs(latestTimeline);
        assertThat(result.failureCategory()).isEqualTo("github_timeout");
        assertThat(result.humanReviewRequired()).isTrue();
        assertThat(result.humanReviewStatus()).isEqualTo("pending");
        assertThat(result.humanReviewNote()).isEqualTo("verify manually");
    }

    @Test
    void fallsBackToStartedAtThenCreatedAtForUpdatedAt() {
        ReviewTask startedTask = baseTask();
        startedTask.setStartedAt(LocalDateTime.of(2026, 6, 19, 10, 2));
        assertThat(assembler.assemble(startedTask, baseItem(), null).updatedAt())
            .isEqualTo("2026-06-19 10:02:00");

        ReviewTask queuedTask = baseTask();
        assertThat(assembler.assemble(queuedTask, baseItem(), null).updatedAt())
            .isEqualTo("2026-06-19 10:00:00");
    }

    private ReviewTask baseTask() {
        ReviewTask task = new ReviewTask();
        task.setCreatedAt(LocalDateTime.of(2026, 6, 19, 10, 0));
        return task;
    }

    private ReviewTaskListItem baseItem() {
        return new ReviewTaskListItem(
            521L,
            42,
            "Review task",
            "Hello-World",
            "octocat",
            "abc123",
            "main",
            "completed",
            "high",
            2,
            "completed",
            "github_pr_picker",
            "github_pr_picker",
            "2026-06-19 10:00:00",
            "0 分 37 秒",
            "github_timeout",
            "GitHub API 响应超时",
            "请稍后重试",
            true,
            "pending",
            "verify manually",
            "reviewer",
            "2026-06-19 10:06:00"
        );
    }
}
