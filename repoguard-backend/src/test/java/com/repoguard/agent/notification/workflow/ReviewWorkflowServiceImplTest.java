package com.repoguard.agent.notification.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.config.ReviewWorkflowProperties;
import com.repoguard.agent.dto.NotificationReadRequest;
import com.repoguard.agent.dto.ReviewAssignmentRequest;
import com.repoguard.agent.dto.ReviewBotCommandRequest;
import com.repoguard.agent.dto.ReviewRetryResponse;
import com.repoguard.agent.entity.NotificationReadState;
import com.repoguard.agent.entity.ReviewBotCommandAudit;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.NotificationReadStateMapper;
import com.repoguard.agent.mapper.ReviewBotCommandAuditMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.service.ReviewTaskCommandService;
import com.repoguard.agent.review.task.ReviewTaskTransitionStore;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReviewWorkflowServiceImplTest {

    private final ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final NotificationReadStateMapper readStateMapper = org.mockito.Mockito.mock(NotificationReadStateMapper.class);
    private final ReviewBotCommandAuditMapper botAuditMapper = org.mockito.Mockito.mock(ReviewBotCommandAuditMapper.class);
    private final ReviewTaskCommandService reviewTaskCommandService = org.mockito.Mockito.mock(ReviewTaskCommandService.class);
    private final ReviewTaskTransitionStore transitionStore = org.mockito.Mockito.mock(ReviewTaskTransitionStore.class);
    private final ReviewWorkflowProperties properties = new ReviewWorkflowProperties();
    private final ReviewWorkflowServiceImpl service = new ReviewWorkflowServiceImpl(
        reviewTaskMapper, readStateMapper, botAuditMapper, reviewTaskCommandService, transitionStore, properties
    );

    @BeforeEach
    void resetMocks() {
        reset(reviewTaskMapper, readStateMapper, botAuditMapper, reviewTaskCommandService, transitionStore);
    }

    @Test
    void listsPendingQueueAndMarksOverdueItems() {
        ReviewTask task = task(1L, LocalDateTime.now().minusMinutes(1));
        Page<ReviewTask> page = Page.of(1, 20);
        page.setRecords(List.of(task));
        page.setTotal(1);
        when(reviewTaskMapper.selectPage(any(), any())).thenReturn(page);

        var result = service.listQueue(1, 20, " reviewer ", true);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items().getFirst().assignee()).isEqualTo("reviewer");
        assertThat(result.items().getFirst().overdue()).isTrue();
    }

    @Test
    void assignsTaskWithDefaultSlaAndCanClearAssignment() {
        ReviewTask task = task(2L, null);
        when(reviewTaskMapper.selectById(2L)).thenReturn(task);
        when(transitionStore.assignHumanReview(any(), any(), any(), any())).thenReturn(true);

        var assigned = service.assign(2L, new ReviewAssignmentRequest(" reviewer ", null), "admin");
        assertThat(assigned.assignee()).isEqualTo("reviewer");
        assertThat(assigned.slaDeadline()).isNotBlank();
        assertThat(task.getReviewAssignee()).isEqualTo("reviewer");

        var cleared = service.assign(2L, new ReviewAssignmentRequest(" ", 10), "admin");
        assertThat(cleared.assignee()).isNull();
        verify(transitionStore, org.mockito.Mockito.times(2)).assignHumanReview(any(), any(), any(), any());
    }

    @Test
    void rejectsAssignmentForTerminalTask() {
        ReviewTask task = task(3L, null);
        task.setStatus("COMPLETED");
        when(reviewTaskMapper.selectById(3L)).thenReturn(task);

        assertThatThrownBy(() -> service.assign(3L, new ReviewAssignmentRequest("reviewer", 5), "admin"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("pending human review");
    }

    @Test
    void escalatesOverdueTasksUpToConfiguredLimit() {
        ReviewTask task = task(4L, LocalDateTime.now().minusHours(1));
        ReviewTask atLimit = task(40L, LocalDateTime.now().minusHours(2));
        atLimit.setReviewEscalationLevel(properties.getEscalationLimit());
        ReviewTask conflict = task(41L, LocalDateTime.now().minusHours(3));
        conflict.setReviewEscalationLevel(1);
        when(reviewTaskMapper.selectList(any())).thenReturn(List.of(task, atLimit, conflict));
        when(transitionStore.escalateHumanReview(any(), any(Integer.class), any())).thenReturn(true);
        when(transitionStore.escalateHumanReview(
            org.mockito.ArgumentMatchers.eq(conflict), org.mockito.ArgumentMatchers.eq(1), any()
        )).thenReturn(false);

        var result = service.escalateOverdue();

        assertThat(result.escalated()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(2);
        verify(transitionStore, org.mockito.Mockito.times(2))
            .escalateHumanReview(any(), any(Integer.class), any());
    }

    @Test
    void botReviewCommandIsIdempotentAndAudited() {
        ReviewTask task = task(5L, null);
        task.setStatus("FAILED");
        when(botAuditMapper.selectOne(any())).thenReturn(null);
        when(reviewTaskCommandService.retryReview(5L)).thenReturn(new ReviewRetryResponse(5L, "queued", "ok", 1));

        var response = service.executeBotCommand(
            "github", new ReviewBotCommandRequest("cmd-1", "/repoguard review 5", null), "alice"
        );

        assertThat(response.status()).isEqualTo("ACCEPTED");
        assertThat(response.taskId()).isEqualTo(5L);
        verify(botAuditMapper).insert(any(com.repoguard.agent.entity.ReviewBotCommandAudit.class));
        verify(reviewTaskCommandService).retryReview(5L);
    }

    @Test
    void botStatusAndInvalidCommandReturnActionableResponses() {
        ReviewTask task = task(6L, null);
        when(botAuditMapper.selectOne(any())).thenReturn(null);
        when(reviewTaskMapper.selectById(6L)).thenReturn(task);

        var status = service.executeBotCommand(
            "gitlab", new ReviewBotCommandRequest("cmd-status", "/repoguard status", 6L), "alice"
        );
        var invalid = service.executeBotCommand(
            "gitlab", new ReviewBotCommandRequest("cmd-invalid", "hello", null), "alice"
        );

        assertThat(status.message()).contains("当前状态");
        assertThat(invalid.status()).isEqualTo("REJECTED");
        verify(botAuditMapper, org.mockito.Mockito.times(2)).insert(any(com.repoguard.agent.entity.ReviewBotCommandAudit.class));
    }

    @Test
    void persistsReadStateAndReturnsKeys() {
        when(readStateMapper.selectOne(any())).thenReturn(null);
        service.markNotificationRead(new NotificationReadRequest("review-failed-1"), "alice");
        verify(readStateMapper).insert(any(NotificationReadState.class));

        NotificationReadState state = new NotificationReadState();
        state.setId(10L);
        state.setNotificationKey("review-failed-1");
        when(readStateMapper.selectOne(any())).thenReturn(state);
        service.markNotificationRead(new NotificationReadRequest("review-failed-1"), "alice");
        verify(readStateMapper).updateById(state);
    }

    @Test
    void listsPersistedReadKeysForAuthenticatedReader() {
        NotificationReadState first = new NotificationReadState();
        first.setNotificationKey("review-failed-1");
        NotificationReadState second = new NotificationReadState();
        second.setNotificationKey("review-sla-overdue-2");
        when(readStateMapper.selectList(any())).thenReturn(List.of(first, second));

        assertThat(service.listReadNotificationKeys(" alice "))
            .containsExactly("review-failed-1", "review-sla-overdue-2");
    }

    @Test
    void rejectsUnsupportedProviderAndMissingTask() {
        assertThatThrownBy(() -> service.executeBotCommand(
            "unknown", new ReviewBotCommandRequest("cmd-unsupported", "/repoguard status 1", 1L), "alice"
        )).isInstanceOf(BusinessException.class).hasMessageContaining("Unsupported bot provider");

        when(reviewTaskMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.assign(99L, new ReviewAssignmentRequest("reviewer", 10), "alice"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("Review task not found");
    }

    @Test
    void botCommandsReturnActionableResponsesForRejectedAndFailedOperations() {
        when(botAuditMapper.selectOne(any())).thenReturn(null);
        when(reviewTaskCommandService.retryReview(10L)).thenThrow(new IllegalStateException("queue unavailable"));
        var retryFailed = service.executeBotCommand(
            "github", new ReviewBotCommandRequest("cmd-retry-failed", "/repoguard review 10", null), "alice"
        );
        assertThat(retryFailed.status()).isEqualTo("REJECTED");
        assertThat(retryFailed.message()).contains("queue unavailable");

        var missingAssignee = service.executeBotCommand(
            "gitlab", new ReviewBotCommandRequest("cmd-assign-missing", "/repoguard assign 10", null), "alice"
        );
        assertThat(missingAssignee.status()).isEqualTo("REJECTED");

        ReviewTask pending = task(10L, null);
        when(reviewTaskMapper.selectById(10L)).thenReturn(pending);
        when(transitionStore.assignHumanReview(any(), any(), any(), any())).thenReturn(false);
        var assignmentFailed = service.executeBotCommand(
            "gitee", new ReviewBotCommandRequest("cmd-assign-failed", "/repoguard assign 10 reviewer", null), "alice"
        );
        assertThat(assignmentFailed.status()).isEqualTo("REJECTED");
        assertThat(assignmentFailed.message()).contains("Review task changed");

        when(reviewTaskMapper.selectById(11L)).thenReturn(null);
        var missingStatus = service.executeBotCommand(
            "bitbucket", new ReviewBotCommandRequest("cmd-status-missing", "/repoguard status 11", null), "alice"
        );
        assertThat(missingStatus.status()).isEqualTo("REJECTED");

        var unsupportedCommand = service.executeBotCommand(
            "github", new ReviewBotCommandRequest("cmd-unsupported-command", "/repoguard pause 10", null), "alice"
        );
        assertThat(unsupportedCommand.status()).isEqualTo("REJECTED");
        assertThat(unsupportedCommand.message()).contains("不支持的命令");
    }

    @Test
    void returnsPreviousBotAuditWithoutExecutingCommandAgain() {
        ReviewBotCommandAudit previous = new ReviewBotCommandAudit();
        previous.setCommandText(null);
        previous.setStatus("ACCEPTED");
        previous.setTaskId(12L);
        previous.setResponseMessage("已处理");
        when(botAuditMapper.selectOne(any())).thenReturn(previous);

        var response = service.executeBotCommand(
            "github", new ReviewBotCommandRequest("cmd-duplicate", "/repoguard review 12", null), "alice"
        );

        assertThat(response.command()).isEqualTo("unknown");
        assertThat(response.status()).isEqualTo("ACCEPTED");
        verify(botAuditMapper, org.mockito.Mockito.never()).insert(any(ReviewBotCommandAudit.class));
        verify(reviewTaskCommandService, org.mockito.Mockito.never()).retryReview(any());

        ReviewBotCommandAudit previousCommand = new ReviewBotCommandAudit();
        previousCommand.setCommandText("/repoguard review 12");
        previousCommand.setStatus("ACCEPTED");
        previousCommand.setTaskId(12L);
        previousCommand.setResponseMessage("已重新排队审查");
        when(botAuditMapper.selectOne(any())).thenReturn(previousCommand);
        assertThat(service.executeBotCommand(
            "github", new ReviewBotCommandRequest("cmd-duplicate-2", "/repoguard review 12", null), "alice"
        ).command()).isEqualTo("review");
    }

    @Test
    void rejectsInvalidReportPeriod() {
        assertThatThrownBy(() -> service.report("monthly"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("DAILY or WEEKLY");
    }

    @Test
    void buildsDailyAndWeeklyReportWithRiskAndSlaCounts() {
        ReviewTask completed = task(7L, null);
        completed.setStatus("COMPLETED");
        completed.setRiskLevel("HIGH");
        ReviewTask overdue = task(8L, LocalDateTime.now().minusMinutes(2));
        when(reviewTaskMapper.selectList(any())).thenReturn(List.of(completed, overdue));

        var report = service.report("weekly");

        assertThat(report.period()).isEqualTo("WEEKLY");
        assertThat(report.totalReviews()).isEqualTo(2);
        assertThat(report.completedReviews()).isEqualTo(1);
        assertThat(report.highRiskReviews()).isEqualTo(1);
        assertThat(report.overdueHumanReviews()).isEqualTo(1);
    }

    private ReviewTask task(Long id, LocalDateTime deadline) {
        ReviewTask task = new ReviewTask();
        task.setId(id);
        task.setRepository("repo");
        task.setPrNumber(1);
        task.setTitle("change");
        task.setStatus("PENDING_HUMAN_REVIEW");
        task.setHumanReviewStatus("PENDING");
        task.setReviewAssignee("reviewer");
        task.setReviewSlaDeadline(deadline);
        task.setReviewEscalationLevel(0);
        task.setCreatedAt(LocalDateTime.now().minusHours(1));
        return task;
    }
}
