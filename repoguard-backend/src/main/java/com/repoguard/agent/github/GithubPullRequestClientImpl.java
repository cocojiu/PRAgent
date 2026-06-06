package com.repoguard.agent.github;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.security.SecretCryptoService;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class GithubPullRequestClientImpl implements GithubPullRequestClient {

    private static final String GITHUB_PROVIDER = "GITHUB";

    private final IntegrationConfigMapper integrationConfigMapper;
    private final RestClient restClient;
    private final SecretCryptoService secretCryptoService;

    public GithubPullRequestClientImpl(
        IntegrationConfigMapper integrationConfigMapper,
        RestClient.Builder restClientBuilder,
        SecretCryptoService secretCryptoService
    ) {
        this.integrationConfigMapper = integrationConfigMapper;
        this.restClient = restClientBuilder.build();
        this.secretCryptoService = secretCryptoService;
    }

    @Override
    public GithubPullRequestDiff fetchPullRequestDiff(ReviewTask task) {
        IntegrationConfig config = loadGithubConfig();
        String owner = choose(task.getOrganization(), config == null ? null : config.getDefaultOwner());
        String repository = choose(task.getRepository(), config == null ? null : config.getDefaultRepo());
        if (!StringUtils.hasText(owner) || !StringUtils.hasText(repository)) {
            throw new IllegalStateException("GitHub owner or repository is not configured");
        }

        String baseUrl = config != null && StringUtils.hasText(config.getBaseUrl())
            ? config.getBaseUrl().trim()
            : "https://api.github.com";
        String url = UriComponentsBuilder
            .fromUriString(baseUrl)
            .path("/repos/{owner}/{repo}/pulls/{pullNumber}/files")
            .build(owner, repository, task.getPrNumber())
            .toString();

        try {
            GithubChangedFile[] files = restClient.get()
                .uri(url)
                .headers(headers -> applyGithubHeaders(headers, config))
                .retrieve()
                .body(GithubChangedFile[].class);

            markGithubChecked(config, null);
            List<GithubChangedFile> changedFiles = files == null ? List.of() : Arrays.asList(files);
            return new GithubPullRequestDiff(owner, repository, task.getPrNumber(), changedFiles);
        } catch (RuntimeException ex) {
            markGithubChecked(config, ex.getMessage());
            throw ex;
        }
    }

    private void applyGithubHeaders(HttpHeaders headers, IntegrationConfig config) {
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        String token = config == null ? null : secretCryptoService.decrypt(config.getTokenValue());
        if (StringUtils.hasText(token)) {
            headers.setBearerAuth(token.trim());
        }
    }

    private IntegrationConfig loadGithubConfig() {
        return integrationConfigMapper.selectOne(
            new LambdaQueryWrapper<IntegrationConfig>().eq(IntegrationConfig::getProvider, GITHUB_PROVIDER)
        );
    }

    private void markGithubChecked(IntegrationConfig config, String error) {
        if (config == null || config.getId() == null) {
            return;
        }
        config.setLastCheckedAt(LocalDateTime.now());
        config.setLastError(error);
        config.setStatus(error == null ? "CONFIGURED" : "FAILED");
        config.setUpdatedAt(LocalDateTime.now());
        integrationConfigMapper.updateById(config);
    }

    private String choose(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary.trim() : fallback;
    }
}
