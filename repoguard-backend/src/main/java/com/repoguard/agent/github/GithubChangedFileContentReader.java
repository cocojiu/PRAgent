package com.repoguard.agent.github;

import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.external.ExternalHttpResponseProfile;
import com.repoguard.agent.external.ExternalHttpResponseReader;
import com.repoguard.agent.external.OutboundEndpointPolicy;
import com.repoguard.agent.external.OutboundEndpointType;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class GithubChangedFileContentReader {

    private static final MediaType GITHUB_RAW_JSON = MediaType.parseMediaType(
        "application/vnd.github.raw+json"
    );

    private final RestClient restClient;
    private final ExternalHttpResponseReader responseReader;
    private final OutboundEndpointPolicy endpointPolicy;

    @Autowired
    public GithubChangedFileContentReader(
        RestClient.Builder restClientBuilder,
        ExternalHttpResponseReader responseReader,
        OutboundEndpointPolicy endpointPolicy
    ) {
        this(restClientBuilder, responseReader, endpointPolicy, true);
    }

    GithubChangedFileContentReader(
        RestClient.Builder restClientBuilder,
        ExternalHttpResponseReader responseReader
    ) {
        this(restClientBuilder, responseReader, null, true);
    }

    private GithubChangedFileContentReader(
        RestClient.Builder restClientBuilder,
        ExternalHttpResponseReader responseReader,
        OutboundEndpointPolicy endpointPolicy,
        boolean ignored
    ) {
        this.restClient = GithubRestClientFactory.build(Objects.requireNonNull(restClientBuilder, "restClientBuilder"));
        this.responseReader = Objects.requireNonNull(responseReader, "responseReader");
        this.endpointPolicy = endpointPolicy;
    }

    public String fetch(
        GithubIntegrationSettings settings,
        String baseUrl,
        String owner,
        String repository,
        String headSha,
        String filePath,
        ExternalCallResilience resilience
    ) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(resilience, "resilience");
        String[] pathSegments = validatedPathSegments(filePath);
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
            .pathSegment("repos", owner, repository, "contents")
            .pathSegment(pathSegments)
            .queryParam("ref", requiredText(headSha, "headSha"))
            .build()
            .encode()
            .toUri();
        if (endpointPolicy != null) {
            endpointPolicy.validate(OutboundEndpointType.GITHUB, uri.toString());
        }
        return resilience.github("fetch_changed_file_context", () -> restClient.get()
            .uri(uri)
            .headers(headers -> applyGithubHeaders(headers, settings))
            .exchange((request, response) -> readBody(response)));
    }

    private String readBody(org.springframework.http.client.ClientHttpResponse response) throws IOException {
        byte[] body = responseReader.readSuccessfulBody(
            response,
            "GitHub fetch_changed_file_context failed",
            ExternalHttpResponseProfile.GITHUB
        );
        return new String(body, StandardCharsets.UTF_8);
    }

    private void applyGithubHeaders(HttpHeaders headers, GithubIntegrationSettings settings) {
        headers.setAccept(List.of(GITHUB_RAW_JSON));
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        if (StringUtils.hasText(settings.token())) {
            headers.setBearerAuth(settings.token().trim());
        }
    }

    private String[] validatedPathSegments(String filePath) {
        String normalized = requiredText(filePath, "filePath").replace('\\', '/');
        if (normalized.startsWith("/") || normalized.endsWith("/")) {
            throw new IllegalArgumentException("GitHub file path must be repository-relative");
        }
        String[] segments = normalized.split("/");
        if (segments.length == 0 || Arrays.stream(segments)
            .anyMatch(segment -> segment.isBlank() || ".".equals(segment) || "..".equals(segment))) {
            throw new IllegalArgumentException("GitHub file path contains an invalid segment");
        }
        return segments;
    }

    private String requiredText(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
