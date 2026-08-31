package com.repoguard.agent.messaging.health;

import com.repoguard.agent.config.CacheNames;
import com.repoguard.agent.config.EnterpriseEditionEnabled;
import com.repoguard.agent.dto.MessageQueueHealthResponse;
import com.repoguard.agent.dto.MessageQueueRequeueResponse;
import com.repoguard.agent.review.task.ReviewTaskRequeueService;
import com.repoguard.agent.service.MessageQueueHealthService;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
@EnterpriseEditionEnabled
public class MessageQueueHealthServiceImpl implements MessageQueueHealthService {

    private final MessageQueueHealthQueryService healthQueryService;
    private final ReviewTaskRequeueService requeueService;

    @Autowired
    public MessageQueueHealthServiceImpl(
        MessageQueueHealthQueryService healthQueryService,
        ReviewTaskRequeueService requeueService
    ) {
        this.healthQueryService = Objects.requireNonNull(healthQueryService, "healthQueryService");
        this.requeueService = Objects.requireNonNull(requeueService, "requeueService");
    }

    @Override
    @Cacheable(cacheNames = CacheNames.MESSAGE_QUEUE_HEALTH, key = "'health'", sync = true)
    public MessageQueueHealthResponse getHealth() {
        return healthQueryService.getHealth();
    }

    @Override
    @CacheEvict(cacheNames = CacheNames.MESSAGE_QUEUE_HEALTH, allEntries = true)
    public MessageQueueRequeueResponse requeueTask(Long taskId) {
        return requeueService.requeueTask(taskId);
    }
}
