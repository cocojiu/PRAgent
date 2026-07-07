package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import com.repoguard.agent.config.RabbitReviewQueueProperties;
import com.repoguard.agent.dto.MessageQueueMetricDto;
import com.repoguard.agent.mapper.ReviewTaskMapper.MessageQueueHealthSummary;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class MessageQueueMetricAssemblerTest {

    private final RabbitReviewQueueProperties properties = new RabbitReviewQueueProperties();

    @Test
    void assemblesMetricCardsAndRecordsQueueDepth() {
        properties.setQueue("review.queue");
        properties.setDeadLetterQueue("review.dlq");
        RepoGuardMetrics metrics = org.mockito.Mockito.mock(RepoGuardMetrics.class);
        MessageQueueMetricAssembler assembler = new MessageQueueMetricAssembler(properties, metrics);

        List<MessageQueueMetricDto> result = assembler.assemble(summary(8L, 2L, 1L, 1L, 3L, 1L));
        Map<String, MessageQueueMetricDto> byLabel = result.stream()
            .collect(Collectors.toMap(MessageQueueMetricDto::label, metric -> metric));

        assertThat(byLabel.get("Publish succeeded").value()).isEqualTo("3");
        assertThat(byLabel.get("Publish failed").noteClass()).isEqualTo("trend danger");
        assertThat(byLabel.get("Execution timeout").value()).isEqualTo("1");
        assertThat(byLabel.get("Requeue pending").value()).isEqualTo("1");
        assertThat(byLabel.get("Compensating").value()).isEqualTo("3");
        assertThat(byLabel.get("DLQ backlog").value()).isEqualTo("1");
        verify(metrics).rabbitQueueDepth("review.queue", "publish_failed", 2L);
        verify(metrics).rabbitQueueDepth("review.queue", "execution_timeout", 1L);
        verify(metrics).rabbitQueueDepth("review.queue", "requeue_pending", 1L);
        verify(metrics).rabbitQueueDepth("review.queue", "claimed", 3L);
        verify(metrics).rabbitQueueDepth("review.dlq", "dlq", 1L);
    }

    @Test
    void treatsMissingCountsAsZeroAndNeverShowsNegativeSuccessCount() {
        RepoGuardMetrics metrics = org.mockito.Mockito.mock(RepoGuardMetrics.class);
        MessageQueueMetricAssembler assembler = new MessageQueueMetricAssembler(properties, metrics);

        List<MessageQueueMetricDto> result = assembler.assemble(summary(1L, 4L, null, null, null, 2L));
        Map<String, MessageQueueMetricDto> byLabel = result.stream()
            .collect(Collectors.toMap(MessageQueueMetricDto::label, metric -> metric));

        assertThat(byLabel.get("Publish succeeded").value()).isEqualTo("0");
        assertThat(byLabel.get("Execution timeout").value()).isEqualTo("0");
        assertThat(byLabel.get("Requeue pending").noteClass()).isEqualTo("trend");
        assertThat(byLabel.get("Compensating").value()).isEqualTo("0");
    }

    @Test
    void constructorRejectsMissingMetrics() {
        assertThatThrownBy(() -> new MessageQueueMetricAssembler(properties, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("metrics");
    }

    @Test
    void nullSummaryProducesZeroCardsAndRecordsZeroQueueDepth() {
        RepoGuardMetrics metrics = org.mockito.Mockito.mock(RepoGuardMetrics.class);
        MessageQueueMetricAssembler assembler = new MessageQueueMetricAssembler(properties, metrics);

        List<MessageQueueMetricDto> result = assembler.assemble(null);

        assertThat(result).hasSize(6);
        assertThat(result).allMatch(metric -> "0".equals(metric.value()));
        verify(metrics).rabbitQueueDepth(properties.getQueue(), "publish_failed", 0L);
        verify(metrics).rabbitQueueDepth(properties.getQueue(), "execution_timeout", 0L);
        verify(metrics).rabbitQueueDepth(properties.getQueue(), "requeue_pending", 0L);
        verify(metrics).rabbitQueueDepth(properties.getQueue(), "claimed", 0L);
        verify(metrics).rabbitQueueDepth(properties.getDeadLetterQueue(), "dlq", 0L);
    }

    private MessageQueueHealthSummary summary(
        Long total,
        Long publishFailed,
        Long executionTimeout,
        Long requeuePending,
        Long claimed,
        Long dlqBacklog
    ) {
        MessageQueueHealthSummary summary = new MessageQueueHealthSummary();
        summary.setTotal(total);
        summary.setPublishFailed(publishFailed);
        summary.setExecutionTimeout(executionTimeout);
        summary.setRequeuePending(requeuePending);
        summary.setClaimed(claimed);
        summary.setDlqBacklog(dlqBacklog);
        return summary;
    }
}
