package com.repoguard.agent.service.impl;

import com.repoguard.agent.dto.MessageQueueHealthResponse;
import com.repoguard.agent.dto.MessageQueueRequeueResponse;
import com.repoguard.agent.service.MessageQueueHealthService;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
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
    public MessageQueueHealthResponse getHealth() {
        return healthQueryService.getHealth();
    }

    @Override
    public MessageQueueRequeueResponse requeueTask(Long taskId) {
        return requeueService.requeueTask(taskId);
    }
}
