package com.repoguard.agent.worker;

import com.repoguard.agent.messaging.ReviewTaskMessage;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ReviewTaskWorker {

    private final ReviewTaskExecutor reviewTaskExecutor;

    public ReviewTaskWorker(ReviewTaskExecutor reviewTaskExecutor) {
        this.reviewTaskExecutor = reviewTaskExecutor;
    }

    @RabbitListener(queues = "${app.rabbit.review.queue}", concurrency = "${app.rabbit.review.worker-concurrency:1}")
    public void handle(ReviewTaskMessage message) {
        reviewTaskExecutor.execute(message);
    }
}
