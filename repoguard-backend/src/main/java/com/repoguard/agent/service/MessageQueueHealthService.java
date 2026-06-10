package com.repoguard.agent.service;

import com.repoguard.agent.dto.MessageQueueHealthResponse;

public interface MessageQueueHealthService {

    MessageQueueHealthResponse getHealth();
}
