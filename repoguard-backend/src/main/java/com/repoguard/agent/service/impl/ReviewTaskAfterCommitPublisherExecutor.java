package com.repoguard.agent.service.impl;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class ReviewTaskAfterCommitPublisherExecutor implements Executor {

    private final ExecutorService delegate;

    @Autowired
    public ReviewTaskAfterCommitPublisherExecutor(
        @Qualifier(ReviewTaskPublishExecutorConfig.REVIEW_TASK_AFTER_COMMIT_EXECUTOR) ExecutorService delegate
    ) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(command);
    }
}
