package com.repoguard.agent.messaging.health;

import com.repoguard.agent.messaging.RabbitMqIntegrationProvider;
import com.repoguard.agent.messaging.RabbitMqIntegrationSettings;
import com.repoguard.agent.config.RabbitReviewQueueProperties;
import com.repoguard.agent.dto.MessageQueueExceptionTaskDto;
import com.repoguard.agent.dto.MessageQueueHealthResponse;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper.MessageQueueHealthSummary;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class MessageQueueHealthQueryService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ReviewTaskMapper reviewTaskMapper;
    private final RabbitReviewQueueProperties properties;
    private final RabbitMqIntegrationProvider rabbitMqIntegrationProvider;
    private final MessageQueueRuntimeConfigAssembler runtimeConfigAssembler;
    private final MessageQueueExceptionTaskAssembler exceptionTaskAssembler;
    private final MessageQueueMetricAssembler metricAssembler;

    @Autowired
    MessageQueueHealthQueryService(
        ReviewTaskMapper reviewTaskMapper,
        RabbitReviewQueueProperties properties,
        RabbitMqIntegrationProvider rabbitMqIntegrationProvider,
        MessageQueueRuntimeConfigAssembler runtimeConfigAssembler,
        MessageQueueExceptionTaskAssembler exceptionTaskAssembler,
        MessageQueueMetricAssembler metricAssembler
    ) {
        this.reviewTaskMapper = Objects.requireNonNull(reviewTaskMapper, "reviewTaskMapper");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.rabbitMqIntegrationProvider = Objects.requireNonNull(
            rabbitMqIntegrationProvider,
            "rabbitMqIntegrationProvider"
        );
        this.runtimeConfigAssembler = Objects.requireNonNull(runtimeConfigAssembler, "runtimeConfigAssembler");
        this.exceptionTaskAssembler = Objects.requireNonNull(exceptionTaskAssembler, "exceptionTaskAssembler");
        this.metricAssembler = Objects.requireNonNull(metricAssembler, "metricAssembler");
    }

    MessageQueueHealthResponse getHealth() {
        LocalDateTime createdAfter = LocalDateTime.now()
            .minusDays(Math.max(1, properties.getHealthQueryWindowDays()));
        MessageQueueHealthSummary summary = reviewTaskMapper.selectMessageQueueHealthSummary(createdAfter);
        List<ReviewTask> exceptionTasks = reviewTaskMapper.selectMessageQueueExceptionTasks();
        String latestFailureReason = reviewTaskMapper.selectLatestPublishFailureReason(createdAfter);
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
