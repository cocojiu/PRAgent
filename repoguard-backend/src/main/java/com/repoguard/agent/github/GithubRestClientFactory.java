package com.repoguard.agent.github;

import com.repoguard.agent.external.ExternalHttpRequestFactory;
import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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

    static SimpleClientHttpRequestFactory requestFactory() {
        return ExternalHttpRequestFactory.simple(CONNECT_TIMEOUT, READ_TIMEOUT);
    }
}
