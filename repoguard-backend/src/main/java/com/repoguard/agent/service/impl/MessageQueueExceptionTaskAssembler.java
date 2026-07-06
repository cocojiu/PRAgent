package com.repoguard.agent.service.impl;

import com.repoguard.agent.dto.MessageQueueExceptionTaskDto;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

class MessageQueueExceptionTaskAssembler {

    private static final String STATUS_DLQ = "DLQ";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ReviewTaskStateMachine reviewTaskStateMachine;

    MessageQueueExceptionTaskAssembler(ReviewTaskStateMachine reviewTaskStateMachine) {
        this.reviewTaskStateMachine = reviewTaskStateMachine == null
            ? new ReviewTaskStateMachine()
            : reviewTaskStateMachine;
    }

    List<MessageQueueExceptionTaskDto> assemble(List<ReviewTask> tasks, int maxAttempts) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        int normalizedMaxAttempts = Math.max(1, maxAttempts);
        return tasks.stream()
            .filter(this::isExceptionTask)
            .sorted(Comparator.comparing(ReviewTask::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
            .limit(20)
            .map(task -> toDto(task, normalizedMaxAttempts))
            .toList();
    }

    private MessageQueueExceptionTaskDto toDto(ReviewTask task, int maxAttempts) {
        return new MessageQueueExceptionTaskDto(
            task.getId(),
            task.getOrganization(),
            task.getRepository(),
            task.getPrNumber(),
            exceptionStatus(task, maxAttempts),
            task.getPublishAttempts(),
            format(task.getNextPublishRetryAt()),
            task.getPublishClaimedBy(),
            format(task.getPublishClaimedAt()),
            task.getLastPublishError()
        );
    }

    private boolean isExceptionTask(ReviewTask task) {
        return task != null && (isPublishFailed(task) || reviewTaskStateMachine.isExecutionTimeout(task.getStatus())
            || reviewTaskStateMachine.isRequeuePending(task.getStatus())
            || STATUS_DLQ.equals(task.getStatus()));
    }

    private String exceptionStatus(ReviewTask task, int maxAttempts) {
        if (STATUS_DLQ.equals(task.getStatus())) {
            return STATUS_DLQ;
        }
        if (isRetryExhausted(task, maxAttempts)) {
            return "RETRY_EXHAUSTED";
        }
        if (task.getPublishClaimedAt() != null) {
            return "PUBLISH_CLAIMED";
        }
        return task.getStatus();
    }

    private boolean isPublishFailed(ReviewTask task) {
        return reviewTaskStateMachine.isPublishFailed(task.getStatus());
    }

    private boolean isRetryExhausted(ReviewTask task, int maxAttempts) {
        return isPublishFailed(task) && safeAttempts(task) >= maxAttempts;
    }

    private int safeAttempts(ReviewTask task) {
        return task.getPublishAttempts() == null ? 0 : task.getPublishAttempts();
    }

    private String format(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME_FORMATTER);
    }
}
