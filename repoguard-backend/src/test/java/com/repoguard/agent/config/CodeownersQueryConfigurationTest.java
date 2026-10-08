package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.controller.CodeownersRecommendationController;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.github.GithubChangedFileContentReader;
import com.repoguard.agent.github.GithubIntegrationProvider;
import com.repoguard.agent.github.GithubPullRequestHeadReader;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.notification.workflow.CodeownersQueryService;
import com.repoguard.agent.tenancy.TenantContext;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

@SuppressWarnings("try")
class CodeownersQueryConfigurationTest {
    @Test void personalAndWorkerDoNotCreateQueryOrEndpoint() {
        for (var settings : java.util.List.of(Map.<String,Object>of("app.edition", "personal"),
            Map.<String,Object>of("app.edition", "enterprise-experimental", "app.runtime.role", "worker",
                "app.runtime.deployment-mode", "split", "app.runtime.api.instance-count", "0"))) {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", settings));
                context.register(CodeownersQueryConfiguration.class, CodeownersRecommendationController.class); context.refresh();
                assertThat(context.getBeansOfType(CodeownersQueryService.class)).isEmpty();
                assertThat(context.getBeansOfType(CodeownersRecommendationController.class)).isEmpty();
            }
        }
    }
    @Test void enterpriseApiIsDisabledWithoutExplicitFlag() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of("app.edition", "enterprise-experimental", "repoguard.codeowners.mapping-file", "bad\u0000path")));
            var tasks = mock(ReviewTaskMapper.class); var github = mock(GithubIntegrationProvider.class);
            context.registerBean(com.repoguard.agent.service.ReviewWorkflowService.class, () -> mock(com.repoguard.agent.service.ReviewWorkflowService.class));
            context.registerBean(ObjectMapper.class, () -> new JacksonConfig().objectMapper());
            context.registerBean(ReviewTaskMapper.class, () -> tasks);
            context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
            context.registerBean(GithubIntegrationProvider.class, () -> github);
            context.registerBean(GithubPullRequestHeadReader.class, () -> mock(GithubPullRequestHeadReader.class));
            context.registerBean(GithubChangedFileContentReader.class, () -> mock(GithubChangedFileContentReader.class));
            context.registerBean(com.repoguard.agent.github.GithubPullRequestPathsReader.class, () -> mock(com.repoguard.agent.github.GithubPullRequestPathsReader.class));
            context.registerBean(ExternalCallResilience.class, () -> mock(ExternalCallResilience.class));
            context.register(CodeownersQueryConfiguration.class, CodeownersRecommendationController.class); context.refresh();
            try (var ignored = TenantContext.withTenant(7L)) {
                assertThat(context.getBean(CodeownersQueryService.class).query(9).status()).isEqualTo("DISABLED");
            }
            verifyNoInteractions(tasks, github);
        }
    }
    @Test void malformedOptionalMappingPathCannotPreventConfigurationOrTriggerNetworkReads() {
        var tasks = mock(ReviewTaskMapper.class); var jdbc = mock(JdbcTemplate.class); var github = mock(GithubIntegrationProvider.class);
        var heads = mock(GithubPullRequestHeadReader.class); var contents = mock(GithubChangedFileContentReader.class);
        var configuration = new CodeownersQueryConfiguration();
        var query = configuration.codeownersQueryService(true, "bad\u0000path", new JacksonConfig().objectMapper(), tasks,
            jdbc, github, heads, contents, mock(ExternalCallResilience.class), mock(com.repoguard.agent.github.GithubPullRequestPathsReader.class));
        var task = new com.repoguard.agent.entity.ReviewTask(); task.setOrganization("owner"); task.setRepository("repo");
        task.setStatus("PENDING_HUMAN_REVIEW"); task.setCurrentAttemptId(12L); task.setCommitSha("a".repeat(40));
        when(tasks.selectById(9L)).thenReturn(task);
        try (var scope = TenantContext.withTenant(7L)) { assertThat(query.query(9).status()).isEqualTo("NOT_CONFIGURED"); }
        verifyNoInteractions(jdbc, github, heads, contents);
    }
}
