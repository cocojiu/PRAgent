package com.repoguard.agent.github;

import com.repoguard.agent.config.GithubIntegrationSettings;
import com.repoguard.agent.external.ExternalCallResilience;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
public class GithubPaginator {

    static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 100;

    private final RestClient restClient;
    private final int maxPages;

    public GithubPaginator(RestClient.Builder restClientBuilder) {
        this(restClientBuilder, MAX_PAGES);
    }

    GithubPaginator(RestClient.Builder restClientBuilder, int maxPages) {
        this.restClient = GithubRestClientFactory.build(restClientBuilder);
        if (maxPages < 1) {
            throw new IllegalArgumentException("maxPages must be positive");
        }
        this.maxPages = maxPages;
    }

    public <T> List<T> fetchPages(
        String operation,
        java.util.function.IntFunction<String> pageUrlBuilder,
        GithubIntegrationSettings settings,
        Class<T[]> responseType,
        ExternalCallResilience resilience
    ) {
        List<T> items = new ArrayList<>();
        for (int page = 1; page <= maxPages; page++) {
            String url = pageUrlBuilder.apply(page);
            ResponseEntity<T[]> response = executeGithub(operation, resilience, () -> restClient.get()
                .uri(url)
                .headers(headers -> applyGithubHeaders(headers, settings))
                .retrieve()
                .toEntity(responseType));
            T[] pageItems = response.getBody();
            if (pageItems == null || pageItems.length == 0) {
                break;
            }
            items.addAll(Arrays.asList(pageItems));
            Boolean hasNextPage = hasNextPage(response.getHeaders());
            if (Boolean.FALSE.equals(hasNextPage) || (hasNextPage == null && pageItems.length < PAGE_SIZE)) {
                break;
            }
            if (page == maxPages) {
                throw new IllegalStateException(
                    "GitHub pagination limit reached operation=" + operation
                        + " pages=" + maxPages
                        + " pageSize=" + PAGE_SIZE
                );
            }
        }
        return items;
    }

    private Boolean hasNextPage(HttpHeaders headers) {
        if (headers == null || headers.get(HttpHeaders.LINK) == null) {
            return null;
        }
        return headers.getOrEmpty(HttpHeaders.LINK).stream()
            .flatMap(value -> Arrays.stream(value.split(",")))
            .map(value -> value.toLowerCase(Locale.ROOT))
            .anyMatch(value -> value.contains("rel=\"next\"") || value.contains("rel=next"));
    }

    private <T> T executeGithub(
        String operation,
        ExternalCallResilience resilience,
        java.util.function.Supplier<T> supplier
    ) {
        return resilience == null ? supplier.get() : resilience.github(operation, supplier);
    }

    private void applyGithubHeaders(HttpHeaders headers, GithubIntegrationSettings settings) {
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        if (StringUtils.hasText(settings.token())) {
            headers.setBearerAuth(settings.token().trim());
        }
    }
}
