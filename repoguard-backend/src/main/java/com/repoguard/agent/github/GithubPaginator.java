package com.repoguard.agent.github;

import com.repoguard.agent.config.GithubIntegrationSettings;
import com.repoguard.agent.external.ExternalCallResilience;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
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
        ExternalCallResilience effectiveResilience = Objects.requireNonNull(resilience, "resilience");
        List<T> items = new ArrayList<>();
        String nextUrl = pageUrlBuilder.apply(1);
        for (int page = 1; page <= maxPages; page++) {
            String url = nextUrl;
            ResponseEntity<T[]> response = executeGithub(operation, effectiveResilience, () -> restClient.get()
                .uri(url)
                .headers(headers -> applyGithubHeaders(headers, settings))
                .retrieve()
                .toEntity(responseType));
            T[] pageItems = response.getBody();
            if (pageItems == null || pageItems.length == 0) {
                break;
            }
            items.addAll(Arrays.asList(pageItems));
            NextPageLink nextPageLink = nextPageLink(response.getHeaders());
            if (nextPageLink.headerPresent()) {
                if (!StringUtils.hasText(nextPageLink.url())) {
                    break;
                }
                nextUrl = nextPageLink.url();
            } else if (pageItems.length < PAGE_SIZE) {
                break;
            } else {
                nextUrl = pageUrlBuilder.apply(page + 1);
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

    private NextPageLink nextPageLink(HttpHeaders headers) {
        if (headers == null || headers.get(HttpHeaders.LINK) == null) {
            return NextPageLink.missing();
        }
        String nextUrl = headers.getOrEmpty(HttpHeaders.LINK).stream()
            .flatMap(value -> Arrays.stream(value.split(",")))
            .filter(this::isNextLink)
            .map(this::linkUrl)
            .filter(StringUtils::hasText)
            .findFirst()
            .orElse(null);
        return new NextPageLink(true, nextUrl);
    }

    private boolean isNextLink(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return normalized.contains("rel=\"next\"") || normalized.contains("rel=next");
    }

    private String linkUrl(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        int start = value.indexOf('<');
        int end = value.indexOf('>', start + 1);
        if (start < 0 || end <= start) {
            return null;
        }
        return value.substring(start + 1, end).trim();
    }

    private <T> T executeGithub(
        String operation,
        ExternalCallResilience resilience,
        java.util.function.Supplier<T> supplier
    ) {
        return resilience.github(operation, supplier);
    }

    private void applyGithubHeaders(HttpHeaders headers, GithubIntegrationSettings settings) {
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        if (StringUtils.hasText(settings.token())) {
            headers.setBearerAuth(settings.token().trim());
        }
    }

    private record NextPageLink(boolean headerPresent, String url) {

        private static NextPageLink missing() {
            return new NextPageLink(false, null);
        }
    }
}
