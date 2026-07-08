package com.repoguard.agent.service.impl;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class GithubCommentPublishExecutor implements Executor {

    private final ExecutorService delegate;

    @Autowired
    public GithubCommentPublishExecutor(
        @Qualifier(GithubCommentPublishExecutorConfig.GITHUB_COMMENT_PUBLISH_EXECUTOR_SERVICE) ExecutorService delegate
    ) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(command);
    }
}
