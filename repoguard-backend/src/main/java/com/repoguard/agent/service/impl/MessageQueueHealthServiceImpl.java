package com.repoguard.agent.service.impl;

import com.repoguard.agent.config.RabbitMqIntegrationProvider;
import com.repoguard.agent.config.RabbitReviewQueueProperties;
import com.repoguard.agent.dto.MessageQueueHealthResponse;
import com.repoguard.agent.dto.MessageQueueRequeueResponse;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.mapper.SystemSettingLogMapper;
import com.repoguard.agent.messaging.ReviewTaskPublishOutboxStore;
import com.repoguard.agent.messaging.ReviewTaskPublisher;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.service.MessageQueueHealthService;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

@Service
public class MessageQueueHealthServiceImpl implements MessageQueueHealthService {

    private final MessageQueueHealthQueryService healthQueryService;
    private final ReviewTaskRequeueService requeueService;

    public MessageQueueHealthServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        SystemSettingLogMapper systemSettingLogMapper,
        RabbitMqIntegrationProvider rabbitMqIntegrationProvider,
        RabbitReviewQueueProperties properties,
        RabbitTemplate rabbitTemplate,
        ReviewTaskPublisher reviewTaskPublisher
    ) {
        this(
            reviewTaskMapper,
            reviewTimelineMapper,
            systemSettingLogMapper,
            rabbitMqIntegrationProvider,
            properties,
            rabbitTemplate,
            reviewTaskPublisher,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    @Autowired
    MessageQueueHealthServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        SystemSettingLogMapper systemSettingLogMapper,
        RabbitMqIntegrationProvider rabbitMqIntegrationProvider,
        RabbitReviewQueueProperties properties,
        RabbitTemplate rabbitTemplate,
        ReviewTaskPublisher reviewTaskPublisher,
        PlatformTransactionManager transactionManager,
        ReviewTaskStateMachine reviewTaskStateMachine,
        MessageQueueHealthQueryService healthQueryService,
        ReviewTaskRequeueService requeueService
    ) {
        this(
            reviewTaskMapper,
            reviewTimelineMapper,
            systemSettingLogMapper,
            rabbitMqIntegrationProvider,
            properties,
            rabbitTemplate,
            reviewTaskPublisher,
            transactionManager,
            null,
            reviewTaskStateMachine,
            null,
            healthQueryService,
            requeueService
        );
    }

    public MessageQueueHealthServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        SystemSettingLogMapper systemSettingLogMapper,
        RabbitMqIntegrationProvider rabbitMqIntegrationProvider,
        RabbitReviewQueueProperties properties,
        RabbitTemplate rabbitTemplate,
        ReviewTaskPublisher reviewTaskPublisher,
        RepoGuardMetrics metrics
    ) {
        this(
            reviewTaskMapper,
            reviewTimelineMapper,
            systemSettingLogMapper,
            rabbitMqIntegrationProvider,
            properties,
            rabbitTemplate,
            reviewTaskPublisher,
            null,
            metrics,
            null,
            null,
            null,
            null
        );
    }

    public MessageQueueHealthServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        SystemSettingLogMapper systemSettingLogMapper,
        RabbitMqIntegrationProvider rabbitMqIntegrationProvider,
        RabbitReviewQueueProperties properties,
        RabbitTemplate rabbitTemplate,
        ReviewTaskPublisher reviewTaskPublisher,
        PlatformTransactionManager transactionManager,
        RepoGuardMetrics metrics,
        ReviewTaskStateMachine reviewTaskStateMachine
    ) {
        this(
            reviewTaskMapper,
            reviewTimelineMapper,
            systemSettingLogMapper,
            rabbitMqIntegrationProvider,
            properties,
            rabbitTemplate,
            reviewTaskPublisher,
            transactionManager,
            metrics,
            reviewTaskStateMachine,
            null,
            null,
            null
        );
    }

    MessageQueueHealthServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        SystemSettingLogMapper systemSettingLogMapper,
        RabbitMqIntegrationProvider rabbitMqIntegrationProvider,
        RabbitReviewQueueProperties properties,
        RabbitTemplate rabbitTemplate,
        ReviewTaskPublisher reviewTaskPublisher,
        PlatformTransactionManager transactionManager,
        RepoGuardMetrics metrics,
        ReviewTaskStateMachine reviewTaskStateMachine,
        MessageQueueAuditRecorder auditRecorder,
        MessageQueueHealthQueryService healthQueryService,
        ReviewTaskRequeueService requeueService
    ) {
        ReviewTaskStateMachine stateMachine = reviewTaskStateMachine == null
            ? new ReviewTaskStateMachine()
            : reviewTaskStateMachine;
        MessageQueueAuditRecorder audit = auditRecorder == null
            ? new MessageQueueAuditRecorder(systemSettingLogMapper)
            : auditRecorder;
        ReviewTaskPublishOutboxStore outboxStore = new ReviewTaskPublishOutboxStore(
            reviewTaskMapper,
            reviewTimelineMapper,
            stateMachine
        );
        this.healthQueryService = healthQueryService == null
            ? new MessageQueueHealthQueryService(
                reviewTaskMapper,
                rabbitMqIntegrationProvider,
                properties,
                rabbitTemplate,
                metrics,
                stateMachine
            )
            : healthQueryService;
        this.requeueService = requeueService == null
            ? new ReviewTaskRequeueService(
                reviewTaskMapper,
                reviewTimelineMapper,
                properties,
                reviewTaskPublisher,
                transactionManager,
                stateMachine,
                audit,
                outboxStore
            )
            : requeueService;
    }

    @Override
    public MessageQueueHealthResponse getHealth() {
        return healthQueryService.getHealth();
    }

    @Override
    public MessageQueueRequeueResponse requeueTask(Long taskId) {
        return requeueService.requeueTask(taskId);
    }
}
