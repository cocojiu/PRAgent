package com.repoguard.agent.service;

import com.repoguard.agent.dto.MessageQueueHealthResponse;
import com.repoguard.agent.dto.MessageQueueRequeueResponse;

public interface MessageQueueHealthService {

    MessageQueueHealthResponse getHealth();

    MessageQueueRequeueResponse requeueTask(Long taskId);
}
