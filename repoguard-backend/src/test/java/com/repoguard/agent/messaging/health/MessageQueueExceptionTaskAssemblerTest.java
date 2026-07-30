package com.repoguard.agent.messaging.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.dto.MessageQueueExceptionTaskDto;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageQueueExceptionTaskAssemblerTest {

    private final MessageQueueExceptionTaskAssembler assembler = new MessageQueueExceptionTaskAssembler(
        new ReviewTaskStateMachine()
    );

    @Test
    void constructorRejectsMissingStateMachine() {
        assertThatThrownBy(() -> new MessageQueueExceptionTaskAssembler(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("reviewTaskStateMachine");
    }

    @Test
    void assemblesOnlyExceptionTasksWithDisplayStatusAndCreatedTimeOrdering() {
        List<MessageQueueExceptionTaskDto> tasks = assembler.assemble(List.of(
            task(1L, "QUEUED", 0, null, null, null, LocalDateTime.of(2026, 6, 10, 20, 0)),
            task(2L, "PUBLISH_FAILED", 1, LocalDateTime.of(2026, 6, 10, 21, 10), null, "publish failed", LocalDateTime.of(2026, 6, 10, 21, 0)),
            task(3L, "PUBLISH_FAILED", 1, null, "worker-a", "claimed", LocalDateTime.of(2026, 6, 10, 21, 1)),
            task(4L, "PUBLISH_FAILED", 3, null, null, "max attempts", LocalDateTime.of(2026, 6, 10, 21, 2)),
            task(5L, "DLQ", 3, null, null, "dead lettered", LocalDateTime.of(2026, 6, 10, 21, 3)),
            task(6L, "EXECUTION_TIMEOUT", 0, null, null, "timeout", LocalDateTime.of(2026, 6, 10, 21, 4)),
            task(7L, "REQUEUE_PENDING", 0, null, null, "requeue", LocalDateTime.of(2026, 6, 10, 21, 5))
        ), 3);

        assertThat(tasks).extracting(MessageQueueExceptionTaskDto::taskId)
            .containsExactly(7L, 6L, 5L, 4L, 3L, 2L);
        assertThat(tasks).extracting(MessageQueueExceptionTaskDto::status)
            .containsExactly("REQUEUE_PENDING", "EXECUTION_TIMEOUT", "DLQ", "RETRY_EXHAUSTED", "PUBLISH_CLAIMED", "PUBLISH_FAILED");
        assertThat(tasks.get(5).nextRetryAt()).isEqualTo("2026-06-10 21:10:00");
        assertThat(tasks.get(4).claimedAt()).isEqualTo("2026-06-10 21:02:00");
    }

    @Test
    void limitsExceptionTasksToTwentyRows() {
        List<ReviewTask> source = new ArrayList<>();
        for (long id = 1; id <= 25; id++) {
            source.add(task(id, "PUBLISH_FAILED", 1, null, null, "publish failed", LocalDateTime.of(2026, 6, 10, 12, (int) id)));
        }

        List<MessageQueueExceptionTaskDto> tasks = assembler.assemble(source, 3);

        assertThat(tasks).hasSize(20);
        assertThat(tasks).extracting(MessageQueueExceptionTaskDto::taskId)
            .containsExactly(25L, 24L, 23L, 22L, 21L, 20L, 19L, 18L, 17L, 16L, 15L, 14L, 13L, 12L, 11L, 10L, 9L, 8L, 7L, 6L);
    }

    @Test
    void normalizesHistoricalStatusValuesBeforeFilteringAndRendering() {
        List<MessageQueueExceptionTaskDto> tasks = assembler.assemble(List.of(
            task(1L, " publish_failed ", 1, null, null, "publish failed", LocalDateTime.of(2026, 6, 10, 21, 0)),
            task(2L, " execution_timeout ", 0, null, null, "timeout", LocalDateTime.of(2026, 6, 10, 21, 1)),
            task(3L, "requeue_pending", 0, null, null, "requeue", LocalDateTime.of(2026, 6, 10, 21, 2)),
            task(4L, "dlq", 0, null, null, "dead", LocalDateTime.of(2026, 6, 10, 21, 3)),
            task(5L, " queued ", 0, null, null, null, LocalDateTime.of(2026, 6, 10, 21, 4))
        ), 3);

        assertThat(tasks).extracting(MessageQueueExceptionTaskDto::taskId)
            .containsExactly(4L, 3L, 2L, 1L);
        assertThat(tasks).extracting(MessageQueueExceptionTaskDto::status)
            .containsExactly("DLQ", "REQUEUE_PENDING", "EXECUTION_TIMEOUT", "PUBLISH_FAILED");
    }

    @Test
    void treatsNullOrEmptySourceAsEmptyResult() {
        assertThat(assembler.assemble(null, 3)).isEmpty();
        assertThat(assembler.assemble(List.of(), 3)).isEmpty();
    }

    private ReviewTask task(
        Long id,
        String status,
        Integer publishAttempts,
        LocalDateTime nextRetryAt,
        String claimedBy,
        String lastError,
        LocalDateTime createdAt
    ) {
        ReviewTask task = new ReviewTask();
        task.setId(id);
        task.setOrganization("cocojiu");
        task.setRepository("PRAgent");
        task.setPrNumber(100 + id.intValue());
        task.setStatus(status);
        task.setLlmStatus("FAILED");
        task.setPublishAttempts(publishAttempts);
        task.setNextPublishRetryAt(nextRetryAt);
        task.setPublishClaimedBy(claimedBy);
        task.setPublishClaimedAt(claimedBy == null ? null : LocalDateTime.of(2026, 6, 10, 21, 2));
        task.setLastPublishError(lastError);
        task.setCreatedAt(createdAt);
        return task;
    }
}
