package com.repoguard.agent.service.impl;

import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.security.SecretCryptoService;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Executes a lightweight GitHub API probe for integration connectivity checks.
 */
public class GithubConnectionProbe {

    private final RestClient.Builder restClientBuilder;
    private final SecretCryptoService secretCryptoService;

    public GithubConnectionProbe(RestClient.Builder restClientBuilder, SecretCryptoService secretCryptoService) {
        this.restClientBuilder = restClientBuilder;
        this.secretCryptoService = secretCryptoService;
    }

    public void probe(IntegrationConfig config) {
        String url = buildGithubTestUrl(config);
        String token = secretCryptoService.decrypt(config.getTokenValue());
        RestClient.RequestHeadersSpec<?> request = restClientBuilder.build()
            .get()
            .uri(url)
            .accept(MediaType.APPLICATION_JSON);
        if (StringUtils.hasText(token)) {
            request.header("Authorization", "Bearer " + token.trim());
        }
        request.header("X-GitHub-Api-Version", "2022-11-28").retrieve().toBodilessEntity();
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
