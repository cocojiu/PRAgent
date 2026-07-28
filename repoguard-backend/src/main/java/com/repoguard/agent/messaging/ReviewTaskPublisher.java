package com.repoguard.agent.messaging;

public interface ReviewTaskPublisher {

    void publish(ReviewTaskMessage message);

    default void publishOnce(ReviewTaskMessage message) {
        publish(message);
    }
}
