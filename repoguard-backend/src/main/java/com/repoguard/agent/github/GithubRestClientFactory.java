package com.repoguard.agent.github;

import com.repoguard.agent.external.ExternalHttpRequestFactory;
import com.repoguard.agent.review.ReviewDeadline;
import java.time.Duration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

public final class GithubRestClientFactory {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private GithubRestClientFactory() {
    }

    public static RestClient build(RestClient.Builder restClientBuilder) {
        return restClientBuilder
            .clone()
            .requestFactory(requestFactory())
            .build();
    }

    static ClientHttpRequestFactory requestFactory() {
        return ExternalHttpRequestFactory.simple(CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    static RestClient withinDeadline(RestClient client, ReviewDeadline deadline) {
        return client.mutate().requestFactory((uri, method) -> {
            deadline.requireRemaining("semantic_context_http");
            Duration remaining = Duration.ofNanos(Math.max(1, Math.min(READ_TIMEOUT.toNanos(), deadline.remainingNanos())));
            return ExternalHttpRequestFactory.simple(CONNECT_TIMEOUT, remaining).createRequest(uri, method);
        }).build();
    }
}
