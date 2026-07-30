package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.task.ReviewTaskMessage;
import com.repoguard.agent.observability.LogContext;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class ReviewExecutionLogTest {

    private final ReviewLogContextFormatter logContextFormatter = new ReviewLogContextFormatter();
    private final ReviewExecutionLog executionLog = new ReviewExecutionLog(
        new ReviewExecutionClock(),
        logContextFormatter
    );

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void usesMessageContextWhenTaskIsMissing() {
        ReviewTaskMessage message = message();

        try (var _ = executionLog.withExecutionContext(message, null)) {
            assertThat(MDC.get(LogContext.TASK_ID)).isEqualTo("42");
            assertThat(MDC.get(LogContext.PR_NUMBER)).isEqualTo("512");
            assertThat(MDC.get(LogContext.REPOSITORY)).isEqualTo("repo-guard-demo/spring-boot-demo");
            assertThat(MDC.get(LogContext.TRACE_ID)).isEqualTo("trace-123");
        }

        assertThat(MDC.get(LogContext.TASK_ID)).isNull();
        assertThat(MDC.get(LogContext.TRACE_ID)).isNull();
    }

    @Test
    void usesLoadedTaskContextWhenTaskExists() {
        ReviewTask task = new ReviewTask();
        task.setId(43L);
        task.setOrganization("octocat");
        task.setRepository("Hello-World");
        task.setPrNumber(7);

        try (var _ = executionLog.withExecutionContext(message(), task)) {
            assertThat(MDC.get(LogContext.TASK_ID)).isEqualTo("43");
            assertThat(MDC.get(LogContext.PR_NUMBER)).isEqualTo("7");
            assertThat(MDC.get(LogContext.REPOSITORY)).isEqualTo("octocat/Hello-World");
        }
    }

    @Test
    void repositorySlugUsesUnknownPartsForSparseTask() {
        ReviewTask task = new ReviewTask();

        assertThat(logContextFormatter.repositorySlug(task)).isEqualTo("<unknown>/<unknown>");
    }

    private ReviewTaskMessage message() {
        return new ReviewTaskMessage(
            42L,
            "repo-guard-demo",
            "spring-boot-demo",
            512,
            "a1b2c3d",
            LocalDateTime.parse("2026-06-05T18:00:00"),
            "trace-123"
        );
    }
}
