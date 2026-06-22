package com.repoguard.agent.github;

import com.repoguard.agent.config.GithubIntegrationSettings;
import com.repoguard.agent.external.ExternalCallResilience;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
public class GithubPaginator {

    static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 100;

    private final RestClient restClient;

    public GithubPaginator(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    public <T> List<T> fetchPages(
        String operation,
        java.util.function.IntFunction<String> pageUrlBuilder,
        GithubIntegrationSettings settings,
        Class<T[]> responseType,
        ExternalCallResilience resilience
    ) {
        List<T> items = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            String url = pageUrlBuilder.apply(page);
            T[] pageItems = executeGithub(operation, resilience, () -> restClient.get()
                .uri(url)
                .headers(headers -> applyGithubHeaders(headers, settings))
                .retrieve()
                .body(responseType));
            if (pageItems == null || pageItems.length == 0) {
                break;
            }
            items.addAll(Arrays.asList(pageItems));
            if (pageItems.length < PAGE_SIZE) {
                break;
            }
        }
        return items;
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
