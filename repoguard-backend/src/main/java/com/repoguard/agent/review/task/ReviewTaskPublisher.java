package com.repoguard.agent.review.task;

public interface ReviewTaskPublisher {

    void publish(ReviewTaskMessage message);

    default void publishOnce(ReviewTaskMessage message) {
        publish(message);
    }
}
