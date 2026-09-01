package com.repoguard.agent.github.checks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.github.GithubAppProperties;
import com.repoguard.agent.github.GithubIntegrationSettings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** Thin HTTP adapter for the GitHub Checks REST API. */
@Component
public class GithubCheckRunGateway {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final GithubAppProperties appProperties;

    public GithubCheckRunGateway(
        RestClient.Builder restClientBuilder,
        ObjectMapper objectMapper,
        GithubAppProperties appProperties
    ) {
        this.restClient = com.repoguard.agent.github.GithubRestClientFactory.build(
            Objects.requireNonNull(restClientBuilder, "restClientBuilder")
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.appProperties = Objects.requireNonNull(appProperties, "appProperties");
    }

    public RemoteCheckRun find(
        GithubIntegrationSettings settings,
        String baseUrl,
        String owner,
        String repository,
        String headSha,
        String name,
        String externalId
    ) {
        String url = UriComponentsBuilder.fromUriString(baseUrl)
            .path("/repos/{owner}/{repo}/commits/{ref}/check-runs")
            .queryParam("check_name", name)
            .queryParam("per_page", 100)
            .buildAndExpand(owner, repository, headSha)
            .toUriString();
        JsonNode root = request(settings, url).get();
        JsonNode runs = root == null ? null : root.path("check_runs");
        if (runs == null || !runs.isArray()) {
            return null;
        }
        for (JsonNode run : runs) {
            if (externalId.equals(run.path("external_id").asText(null))) {
                return remote(run);
            }
        }
        return null;
    }

    public RemoteCheckRun create(
        GithubIntegrationSettings settings,
        String baseUrl,
        String owner,
        String repository,
        CreateRequest request
    ) {
        String url = UriComponentsBuilder.fromUriString(baseUrl)
            .path("/repos/{owner}/{repo}/check-runs")
            .buildAndExpand(owner, repository)
            .toUriString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", request.name());
        body.put("head_sha", request.headSha());
        body.put("status", request.status());
        body.put("external_id", request.externalId());
        body.put("output", outputBody(request.output()));
        return remote(request(settings, url).post(body));
    }

    public RemoteCheckRun update(
        GithubIntegrationSettings settings,
        String baseUrl,
        String owner,
        String repository,
        long checkRunId,
        UpdateRequest request
    ) {
        String url = UriComponentsBuilder.fromUriString(baseUrl)
            .path("/repos/{owner}/{repo}/check-runs/{checkRunId}")
            .buildAndExpand(owner, repository, checkRunId)
            .toUriString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", request.status());
        if (request.conclusion() != null) {
            body.put("conclusion", request.conclusion());
        }
        if (request.startedAt() != null) {
            body.put("started_at", request.startedAt());
        }
        if (request.completedAt() != null) {
            body.put("completed_at", request.completedAt());
        }
        body.put("output", outputBody(request.output()));
        return remote(request(settings, url).patch(body));
    }

    private RequestBuilder request(GithubIntegrationSettings settings, String url) {
        if (settings == null || !StringUtils.hasText(settings.token())) {
            throw new IllegalStateException("GitHub token is not configured for Checks API");
        }
        return new RequestBuilder(url, settings.token());
    }

    private Map<String, Object> outputBody(Output output) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", output.title());
        body.put("summary", output.summary());
        if (StringUtils.hasText(output.text())) {
            body.put("text", output.text());
        }
        if (!output.annotations().isEmpty()) {
            body.put("annotations", output.annotations().stream().map(this::annotationBody).toList());
        }
        return body;
    }

    private Map<String, Object> annotationBody(Annotation annotation) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("path", annotation.path());
        body.put("start_line", annotation.startLine());
        body.put("end_line", annotation.endLine());
        body.put("annotation_level", annotation.annotationLevel());
        body.put("message", annotation.message());
        body.put("title", annotation.title());
        if (StringUtils.hasText(annotation.rawDetails())) {
            body.put("raw_details", annotation.rawDetails());
        }
        return body;
    }

    private RemoteCheckRun remote(JsonNode node) {
        if (node == null || !node.path("id").canConvertToLong()) {
            throw new IllegalStateException("GitHub Checks response did not contain a check run id");
        }
        return new RemoteCheckRun(
            node.path("id").asLong(),
            node.path("external_id").asText(null),
            node.path("status").asText(null),
            node.path("conclusion").asText(null)
        );
    }

    public record CreateRequest(String name, String headSha, String status, String externalId, Output output) {
    }

    public record UpdateRequest(
        String status,
        String conclusion,
        String startedAt,
        String completedAt,
        Output output
    ) {
    }

    public record Output(String title, String summary, String text, List<Annotation> annotations) {
        public Output {
            annotations = annotations == null ? List.of() : List.copyOf(annotations);
        }
    }

    public record Annotation(
        String path,
        int startLine,
        int endLine,
        String annotationLevel,
        String message,
        String title,
        String rawDetails
    ) {
    }

    public record RemoteCheckRun(Long id, String externalId, String status, String conclusion) {
    }

    private final class RequestBuilder {
        private final String url;
        private final String token;

        private RequestBuilder(String url, String token) {
            this.url = url;
            this.token = token;
        }

        private JsonNode get() {
            return parse(restClient.get()
                .uri(url)
                .accept(MediaType.parseMediaType("application/vnd.github+json"))
                .header("Authorization", "Bearer " + token)
                .header("X-GitHub-Api-Version", appProperties.getApiVersion())
                .retrieve()
                .body(String.class));
        }

        private JsonNode post(Object body) {
            return parse(restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.parseMediaType("application/vnd.github+json"))
                .header("Authorization", "Bearer " + token)
                .header("X-GitHub-Api-Version", appProperties.getApiVersion())
                .body(body)
                .retrieve()
                .body(String.class));
        }

        private JsonNode patch(Object body) {
            return parse(restClient.patch()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.parseMediaType("application/vnd.github+json"))
                .header("Authorization", "Bearer " + token)
                .header("X-GitHub-Api-Version", appProperties.getApiVersion())
                .body(body)
                .retrieve()
                .body(String.class));
        }

        private JsonNode parse(String body) {
            try {
                return body == null ? null : objectMapper.readTree(body);
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("GitHub Checks response is not valid JSON", exception);
            }
        }
    }
}
