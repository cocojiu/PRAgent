package com.repoguard.agent.scm;

import com.fasterxml.jackson.databind.JsonNode;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.external.ExternalHttpJsonResponseReader;
import com.repoguard.agent.external.ExternalHttpResponseProfile;
import com.repoguard.agent.external.OutboundEndpointPolicy;
import com.repoguard.agent.external.OutboundEndpointType;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** Shared guarded HTTP plumbing for provider adapters that expose REST APIs. */
final class ScmProviderHttpSupport {

    private final String provider;
    private final String defaultBaseUrl;
    private final ExternalHttpResponseProfile responseProfile;
    private final OutboundEndpointType endpointType;
    private final ScmIntegrationConfigProvider configProvider;
    private final RestClient restClient;
    private final ExternalHttpJsonResponseReader responseReader;
    private final ExternalCallResilience resilience;
    private final OutboundEndpointPolicy endpointPolicy;

    ScmProviderHttpSupport(
        String provider,
        String defaultBaseUrl,
        ExternalHttpResponseProfile responseProfile,
        OutboundEndpointType endpointType,
        ScmIntegrationConfigProvider configProvider,
        RestClient.Builder restClientBuilder,
        ExternalHttpJsonResponseReader responseReader,
        ExternalCallResilience resilience,
        OutboundEndpointPolicy endpointPolicy
    ) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.defaultBaseUrl = Objects.requireNonNull(defaultBaseUrl, "defaultBaseUrl");
        this.responseProfile = Objects.requireNonNull(responseProfile, "responseProfile");
        this.endpointType = Objects.requireNonNull(endpointType, "endpointType");
        this.configProvider = Objects.requireNonNull(configProvider, "configProvider");
        this.restClient = Objects.requireNonNull(restClientBuilder, "restClientBuilder").build();
        this.responseReader = Objects.requireNonNull(responseReader, "responseReader");
        this.resilience = Objects.requireNonNull(resilience, "resilience");
        this.endpointPolicy = endpointPolicy;
    }

    ScmIntegrationSettings settings() {
        return configProvider.settings(provider);
    }

    ScmIntegrationSettings requireSettings() {
        ScmIntegrationSettings settings = settings();
        if (settings == null || !StringUtils.hasText(settings.token())) {
            throw new IllegalStateException(provider + " token is not configured");
        }
        return settings;
    }

    ScmRepositoryRef configuredRepository() {
        ScmIntegrationSettings settings = settings();
        return repository(settings == null ? null : settings.defaultNamespace(),
            settings == null ? null : settings.defaultRepository(), false);
    }

    ScmRepositoryRef repository(String namespace, String name, boolean required) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            if (required) {
                throw new IllegalStateException(provider + " namespace or repository is not configured");
            }
            return null;
        }
        return new ScmRepositoryRef(namespace.trim(), name.trim());
    }

    JsonNode get(String operation, String url, ScmIntegrationSettings settings) {
        return resilience.github(provider.toLowerCase() + "_" + operation, () -> restClient.get()
            .uri(validatedUrl(url))
            .headers(headers -> applyHeaders(headers, settings))
            .exchange((request, response) -> read(response, provider + " " + operation + " failed")));
    }

    JsonNode post(String operation, String url, ScmIntegrationSettings settings, Object body) {
        return resilience.github(provider.toLowerCase() + "_" + operation, () -> restClient.post()
            .uri(validatedUrl(url))
            .headers(headers -> applyHeaders(headers, settings))
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .exchange((request, response) -> read(response, provider + " " + operation + " failed")));
    }

    String apiBase(ScmIntegrationSettings settings, String apiSuffix) {
        String base = settings != null && StringUtils.hasText(settings.baseUrl())
            ? settings.baseUrl().trim() : defaultBaseUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (StringUtils.hasText(apiSuffix) && base.endsWith(apiSuffix)) {
            return base;
        }
        return base + (StringUtils.hasText(apiSuffix) ? apiSuffix : "");
    }

    String projectUrl(ScmIntegrationSettings settings, ScmRepositoryRef repository, String apiSuffix, String suffix) {
        return UriComponentsBuilder.fromUriString(apiBase(settings, apiSuffix))
            .pathSegment(repository.fullName().split("/", -1))
            .path(suffix)
            .build()
            .encode()
            .toUriString();
    }

    String pathUrl(ScmIntegrationSettings settings, String apiSuffix, String... segments) {
        return UriComponentsBuilder.fromUriString(apiBase(settings, apiSuffix))
            .pathSegment(segments)
            .build()
            .encode()
            .toUriString();
    }

    private JsonNode read(org.springframework.http.client.ClientHttpResponse response, String failurePrefix)
        throws IOException {
        return responseReader.readSuccessfulTree(response, failurePrefix, responseProfile);
    }

    private void applyHeaders(HttpHeaders headers, ScmIntegrationSettings settings) {
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(settings.token().trim());
    }

    private String validatedUrl(String url) {
        if (endpointPolicy != null) {
            endpointPolicy.validate(endpointType, url);
        }
        return url;
    }
}
