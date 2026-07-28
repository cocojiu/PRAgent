package com.repoguard.agent.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.task.ReviewTaskMessage;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class LogContextTest {

    @Test
    void withReviewTaskMessagePutsAndClearsMdcValues() {
        ReviewTaskMessage message = new ReviewTaskMessage(
            42L,
            "repo-guard-demo",
            "spring-boot-demo",
            512,
            "a1b2c3d",
            LocalDateTime.parse("2026-06-05T18:00:00"),
            "trace-123"
        );

        try (LogContext.Scope ignored = LogContext.withReviewTaskMessage(message)) {
            assertThat(MDC.get(LogContext.TASK_ID)).isEqualTo("42");
            assertThat(MDC.get(LogContext.PR_NUMBER)).isEqualTo("512");
            assertThat(MDC.get(LogContext.REPOSITORY)).isEqualTo("repo-guard-demo/spring-boot-demo");
            assertThat(MDC.get(LogContext.TRACE_ID)).isEqualTo("trace-123");
        }

        assertThat(MDC.get(LogContext.TASK_ID)).isNull();
        assertThat(MDC.get(LogContext.PR_NUMBER)).isNull();
        assertThat(MDC.get(LogContext.REPOSITORY)).isNull();
        assertThat(MDC.get(LogContext.TRACE_ID)).isNull();
    }

    @Test
    void withReviewTaskCarriesExplicitTraceId() {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        task.setPrNumber(512);
        task.setOrganization("repo-guard-demo");
        task.setRepository("spring-boot-demo");

        try (LogContext.Scope ignored = LogContext.withReviewTask(task, "trace-456")) {
            assertThat(MDC.get(LogContext.TASK_ID)).isEqualTo("42");
            assertThat(MDC.get(LogContext.PR_NUMBER)).isEqualTo("512");
            assertThat(MDC.get(LogContext.REPOSITORY)).isEqualTo("repo-guard-demo/spring-boot-demo");
            assertThat(MDC.get(LogContext.TRACE_ID)).isEqualTo("trace-456");
        }

        assertThat(MDC.get(LogContext.TASK_ID)).isNull();
        assertThat(MDC.get(LogContext.TRACE_ID)).isNull();
    }

    @Test
    void scopeRestoresPreviousMdcValues() {
        MDC.put(LogContext.TASK_ID, "previous-task");
        MDC.put(LogContext.PR_NUMBER, "7");
        MDC.put(LogContext.REPOSITORY, "previous/repo");
        MDC.put(LogContext.TRACE_ID, "previous-trace");

        ReviewTaskMessage message = new ReviewTaskMessage(
            42L,
            "repo-guard-demo",
            "spring-boot-demo",
            512,
            "a1b2c3d",
            LocalDateTime.parse("2026-06-05T18:00:00"),
            "trace-123"
        );

        try (LogContext.Scope ignored = LogContext.withReviewTaskMessage(message)) {
            assertThat(MDC.get(LogContext.TASK_ID)).isEqualTo("42");
            assertThat(MDC.get(LogContext.TRACE_ID)).isEqualTo("trace-123");
        }

        assertThat(MDC.get(LogContext.TASK_ID)).isEqualTo("previous-task");
        assertThat(MDC.get(LogContext.PR_NUMBER)).isEqualTo("7");
        assertThat(MDC.get(LogContext.REPOSITORY)).isEqualTo("previous/repo");
        assertThat(MDC.get(LogContext.TRACE_ID)).isEqualTo("previous-trace");
        MDC.clear();
    }
}
