package com.repoguard.agent.worker;

import com.repoguard.agent.messaging.ReviewTaskMessage;

public interface ReviewTaskExecutor {

    void execute(ReviewTaskMessage message);
}
