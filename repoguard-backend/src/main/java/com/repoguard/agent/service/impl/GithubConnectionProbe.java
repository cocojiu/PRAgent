package com.repoguard.agent.service.impl;

import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.external.ExternalHttpResponseProfile;
import com.repoguard.agent.external.ExternalHttpResponseReader;
import com.repoguard.agent.external.OutboundEndpointPolicy;
import com.repoguard.agent.external.OutboundEndpointType;
import com.repoguard.agent.github.GithubRestClientFactory;
import com.repoguard.agent.security.SecretCryptoService;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Executes a lightweight GitHub API probe for integration connectivity checks.
 */
@Component
public class GithubConnectionProbe implements ConnectionProbe<IntegrationConfig> {

    static final String PROVIDER = "GITHUB";

    private final RestClient.Builder restClientBuilder;
    private final SecretCryptoService secretCryptoService;
    private final ExternalHttpResponseReader responseReader;
    private final OutboundEndpointPolicy endpointPolicy;

    @Autowired
    public GithubConnectionProbe(
        RestClient.Builder restClientBuilder,
        SecretCryptoService secretCryptoService,
        ExternalHttpResponseReader responseReader,
        OutboundEndpointPolicy endpointPolicy
    ) {
        this(restClientBuilder, secretCryptoService, responseReader, endpointPolicy, true);
    }

    public GithubConnectionProbe(
        RestClient.Builder restClientBuilder,
        SecretCryptoService secretCryptoService,
        ExternalHttpResponseReader responseReader
    ) {
        this(restClientBuilder, secretCryptoService, responseReader, null, true);
    }

    private GithubConnectionProbe(
        RestClient.Builder restClientBuilder,
        SecretCryptoService secretCryptoService,
        ExternalHttpResponseReader responseReader,
        OutboundEndpointPolicy endpointPolicy,
        boolean ignored
    ) {
        this.restClientBuilder = Objects.requireNonNull(restClientBuilder, "restClientBuilder");
        this.secretCryptoService = Objects.requireNonNull(secretCryptoService, "secretCryptoService");
        this.responseReader = Objects.requireNonNull(responseReader, "responseReader");
        this.endpointPolicy = endpointPolicy;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public ConnectionProbeResult probe(IntegrationConfig config) {
        String url = buildGithubTestUrl(config);
        if (endpointPolicy != null) {
            endpointPolicy.validate(OutboundEndpointType.GITHUB, url);
        }
        String token = secretCryptoService.decrypt(config.getTokenValue());
        if (!StringUtils.hasText(token)) {
            return new ConnectionProbeResult(false, "failed", "GitHub token is missing or cannot be decrypted");
        }
        RestClient.RequestHeadersSpec<?> request = GithubRestClientFactory.build(restClientBuilder)
            .get()
            .uri(url)
            .accept(MediaType.APPLICATION_JSON);
        request.header("Authorization", "Bearer " + token.trim());
        request.header("X-GitHub-Api-Version", "2022-11-28")
            .exchange((httpRequest, httpResponse) -> responseReader.readSuccessfulBody(
                httpResponse,
                "GitHub connection test failed",
                ExternalHttpResponseProfile.CONNECTION_PROBE
            ));
        return new ConnectionProbeResult(true, "connected", "GitHub connection test succeeded");
    }

    private String buildGithubTestUrl(IntegrationConfig config) {
        String baseUrl = StringUtils.hasText(config.getBaseUrl()) ? config.getBaseUrl().trim() : "https://api.github.com";
        if (StringUtils.hasText(config.getDefaultOwner()) && StringUtils.hasText(config.getDefaultRepo())) {
            return UriComponentsBuilder
                .fromUriString(baseUrl)
                .path("/repos/{owner}/{repo}")
                .build(config.getDefaultOwner().trim(), config.getDefaultRepo().trim())
                .toString();
        }
        return UriComponentsBuilder.fromUriString(baseUrl).path("/rate_limit").toUriString();
    }
}
