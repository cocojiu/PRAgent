package com.repoguard.agent.worker;

import com.repoguard.agent.review.task.ReviewTaskMessage;

public interface ReviewTaskExecutor {

    void execute(ReviewTaskMessage message);
}
