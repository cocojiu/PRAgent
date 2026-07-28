package com.repoguard.agent.github.comment;

import java.util.concurrent.ExecutorService;
import com.repoguard.agent.concurrency.AsyncExecutorProperties;
import com.repoguard.agent.concurrency.BoundedExecutorFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class GithubCommentPublishExecutorConfig {

    static final String GITHUB_COMMENT_PUBLISH_EXECUTOR_SERVICE = "githubCommentPublishExecutorService";

    @Bean(name = GITHUB_COMMENT_PUBLISH_EXECUTOR_SERVICE, destroyMethod = "shutdown")
    ExecutorService githubCommentPublishExecutor(BoundedExecutorFactory factory, AsyncExecutorProperties properties) {
        return factory.create(
            "github-comment-publish",
            properties.getGithubCommentThreads(),
            properties.getGithubCommentQueueCapacity()
        );
    }
}
