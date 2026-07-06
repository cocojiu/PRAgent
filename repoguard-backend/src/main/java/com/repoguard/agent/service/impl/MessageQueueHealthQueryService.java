package com.repoguard.agent.service.impl;

import com.repoguard.agent.config.RabbitMqIntegrationProvider;
import com.repoguard.agent.config.RabbitMqIntegrationSettings;
import com.repoguard.agent.config.RabbitReviewQueueProperties;
import com.repoguard.agent.dto.MessageQueueExceptionTaskDto;
import com.repoguard.agent.dto.MessageQueueHealthResponse;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper.MessageQueueHealthSummary;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class MessageQueueHealthQueryService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ReviewTaskMapper reviewTaskMapper;
    private final RabbitMqIntegrationProvider rabbitMqIntegrationProvider;
    private final MessageQueueRuntimeConfigAssembler runtimeConfigAssembler;
    private final MessageQueueExceptionTaskAssembler exceptionTaskAssembler;
    private final MessageQueueMetricAssembler metricAssembler;

    @Autowired
    MessageQueueHealthQueryService(
        ReviewTaskMapper reviewTaskMapper,
        RabbitMqIntegrationProvider rabbitMqIntegrationProvider,
        RabbitReviewQueueProperties properties,
        RabbitTemplate rabbitTemplate,
        RepoGuardMetrics metrics,
        ReviewTaskStateMachine reviewTaskStateMachine
    ) {
        this(
            reviewTaskMapper,
            rabbitMqIntegrationProvider,
            properties,
            new RabbitRuntimeHealthProbe(rabbitTemplate, properties),
            metrics,
            reviewTaskStateMachine
        );
    }

    MessageQueueHealthQueryService(
        ReviewTaskMapper reviewTaskMapper,
        RabbitMqIntegrationProvider rabbitMqIntegrationProvider,
        RabbitReviewQueueProperties properties,
        RabbitRuntimeHealthProbe runtimeHealthProbe,
        RepoGuardMetrics metrics,
        ReviewTaskStateMachine reviewTaskStateMachine
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.rabbitMqIntegrationProvider = rabbitMqIntegrationProvider;
        ReviewTaskStateMachine stateMachine = Objects.requireNonNull(
            reviewTaskStateMachine,
            "reviewTaskStateMachine"
        );
        this.runtimeConfigAssembler = new MessageQueueRuntimeConfigAssembler(properties, runtimeHealthProbe);
        this.exceptionTaskAssembler = new MessageQueueExceptionTaskAssembler(stateMachine);
        this.metricAssembler = new MessageQueueMetricAssembler(properties, metrics);
    }

    MessageQueueHealthResponse getHealth() {
        MessageQueueHealthSummary summary = reviewTaskMapper.selectMessageQueueHealthSummary();
        List<ReviewTask> exceptionTasks = reviewTaskMapper.selectMessageQueueExceptionTasks();
        String latestFailureReason = reviewTaskMapper.selectLatestPublishFailureReason();
        RabbitMqIntegrationSettings settings = rabbitMqIntegrationProvider.getSettings();
        if (settings == null) {
            settings = RabbitMqIntegrationSettings.empty();
        }

        return new MessageQueueHealthResponse(
            runtimeConfigAssembler.activeConfig(settings),
            runtimeConfigAssembler.topology(),
            metricAssembler.assemble(summary),
            runtimeConfigAssembler.retryCompensation(summary, latestFailureReason),
            exceptionTaskAssembler.assemble(exceptionTasks, runtimeConfigAssembler.maxAttempts()),
            format(LocalDateTime.now()),
            "DATABASE_TASK_STATE"
        );
    }

    private String format(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME_FORMATTER);
    }
}
