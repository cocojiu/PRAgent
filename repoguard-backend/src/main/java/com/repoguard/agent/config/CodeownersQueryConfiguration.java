package com.repoguard.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.github.GithubChangedFileContentReader;
import com.repoguard.agent.github.GithubIntegrationProvider;
import com.repoguard.agent.github.GithubPullRequestHeadReader;
import com.repoguard.agent.github.GithubPullRequestPathsReader;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.notification.workflow.CodeownersQueryService;
import java.nio.file.Path;
import java.nio.file.InvalidPathException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@ApiRuntimeEnabled
@EnterpriseEditionEnabled
public class CodeownersQueryConfiguration {
    @Bean
    public CodeownersQueryService codeownersQueryService(
        @Value("${repoguard.codeowners.enabled:false}") boolean enabled,
        @Value("${repoguard.codeowners.mapping-file:}") String file,
        ObjectMapper json, ReviewTaskMapper tasks, JdbcTemplate jdbc, GithubIntegrationProvider github,
        GithubPullRequestHeadReader heads, GithubChangedFileContentReader contents, ExternalCallResilience resilience, GithubPullRequestPathsReader pathsReader
    ) {
        return new CodeownersQueryService(enabled, mappingPath(enabled, file), json, tasks, jdbc,
            github, heads, contents, resilience, pathsReader);
    }
    private Path mappingPath(boolean enabled, String file) {
        if (!enabled || file == null || file.isBlank()) return null;
        try { return Path.of(file); }
        catch (InvalidPathException invalid) { return null; }
    }
}
