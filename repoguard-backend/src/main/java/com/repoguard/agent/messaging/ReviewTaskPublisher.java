package com.repoguard.agent.messaging;

public interface ReviewTaskPublisher {

    void publish(ReviewTaskMessage message);
}
