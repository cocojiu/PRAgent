package com.repoguard.agent.github.checks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.dto.ReviewRetryResponse;
import com.repoguard.agent.entity.GithubCheckRun;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.GithubCheckRunMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.task.ReviewTaskRetryService;
import com.repoguard.agent.tenancy.TenantRepositoryBinding;
import com.repoguard.agent.tenancy.TenantRepositoryResolver;
import org.junit.jupiter.api.Test;

class GithubCheckRunWebhookServiceTest {

    private final GithubCheckRunProperties properties = enabledProperties();
    private final GithubCheckRunMapper checkRunMapper = mock(GithubCheckRunMapper.class);
    private final ReviewTaskMapper taskMapper = mock(ReviewTaskMapper.class);
    private final ReviewTaskRetryService retryService = mock(ReviewTaskRetryService.class);
    private final TenantRepositoryResolver resolver = mock(TenantRepositoryResolver.class);
    private final GithubCheckRunWebhookService service = new GithubCheckRunWebhookService(
        properties, checkRunMapper, taskMapper, retryService, resolver
    );
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rerunsRegisteredTerminalCheckRunInItsTenant() throws Exception {
        GithubCheckRun record = new GithubCheckRun();
        record.setTaskId(7L);
        record.setExternalId("repoguard-task:7:run:1");
        record.setName("RepoGuard PR Review");
        ReviewTask task = new ReviewTask();
        task.setId(7L);
        task.setStatus("COMPLETED");
        task.setOrganization("octo");
        task.setRepository("repo");
        task.setCommitSha("0123456789abcdef0123456789abcdef01234567");
        when(resolver.resolve("octo", "repo", 77L)).thenReturn(
            new TenantRepositoryBinding(23L, "tenant-23", "octo", "repo", null)
        );
        when(checkRunMapper.selectByGithubCheckRunId(99L)).thenReturn(record);
        when(taskMapper.selectById(7L)).thenReturn(task);
        when(retryService.rerunFromGithubCheck(7L, task.getCommitSha())).thenReturn(
            new ReviewRetryResponse(7L, "queued", "queued", 1)
        );

        var response = service.handle(objectMapper.readTree(payload()), "delivery-1");

        assertThat(response.status()).isEqualTo("queued");
        assertThat(response.taskId()).isEqualTo(7L);
    }

    @Test
    void ignoresDifferentCheckName() throws Exception {
        var response = service.handle(objectMapper.readTree(payload().replace("RepoGuard PR Review", "Other")), "delivery-2");

        assertThat(response.status()).isEqualTo("skipped");
    }

    @Test
    void skipsDisabledAndUnsupportedActionsBeforeParsingThePayload() throws Exception {
        properties.setEnabled(false);
        assertThat(service.handle(objectMapper.readTree("{\"action\":\"rerequested\"}"), "disabled").status())
            .isEqualTo("skipped");

        properties.setEnabled(true);
        assertThat(service.handle(objectMapper.readTree("{\"action\":\"completed\"}"), "ignored").status())
            .isEqualTo("skipped");
        verifyNoPersistenceRead();
    }

    @Test
    void rejectsMalformedPayloadAndUnknownRegistration() throws Exception {
        assertThatThrownBy(() -> service.handle(objectMapper.readTree("{\"action\":\"rerequested\"}"), "bad"))
            .isInstanceOf(com.repoguard.agent.common.BusinessException.class)
            .hasMessageContaining("check_run");

        when(resolver.resolve("octo", "repo", 77L)).thenReturn(
            new TenantRepositoryBinding(23L, "tenant-23", "octo", "repo", null)
        );
        when(checkRunMapper.selectByGithubCheckRunId(99L)).thenReturn(null);
        assertThat(service.handle(objectMapper.readTree(payload()), "unknown").status()).isEqualTo("skipped");
    }

    @Test
    void skipsTaskThatIsMissingOrAlreadyRunning() throws Exception {
        GithubCheckRun record = registeredRecord();
        when(resolver.resolve("octo", "repo", 77L)).thenReturn(
            new TenantRepositoryBinding(23L, "tenant-23", "octo", "repo", null)
        );
        when(checkRunMapper.selectByGithubCheckRunId(99L)).thenReturn(record);
        when(taskMapper.selectById(7L)).thenReturn(null);
        assertThat(service.handle(objectMapper.readTree(payload()), "missing-task").status()).isEqualTo("skipped");

        ReviewTask running = task("REVIEWING");
        when(taskMapper.selectById(7L)).thenReturn(running);
        assertThat(service.handle(objectMapper.readTree(payload()), "running-task").status()).isEqualTo("skipped");
        verify(retryService, never()).rerunFromGithubCheck(any(), any());
    }

    @Test
    void acceptsWebhookWithoutInstallationAndUsesTaskRepositoryIdentity() throws Exception {
        GithubCheckRun record = registeredRecord();
        ReviewTask task = task("COMPLETED");
        when(resolver.resolve("octo", "repo", null)).thenReturn(
            new TenantRepositoryBinding(23L, "tenant-23", "octo", "repo", null)
        );
        when(checkRunMapper.selectByGithubCheckRunId(99L)).thenReturn(record);
        when(taskMapper.selectById(7L)).thenReturn(task);
        when(retryService.rerunFromGithubCheck(7L, task.getCommitSha())).thenReturn(
            new ReviewRetryResponse(7L, "queued", "queued", 2)
        );

        String noInstallation = payload().replace("\"installation\":{\"id\":77}", "\"installation\":null");
        assertThat(service.handle(objectMapper.readTree(noInstallation), "no-installation").status())
            .isEqualTo("queued");
    }

    private GithubCheckRun registeredRecord() {
        GithubCheckRun record = new GithubCheckRun();
        record.setTaskId(7L);
        record.setExternalId("repoguard-task:7:run:1");
        record.setName("RepoGuard PR Review");
        return record;
    }

    private ReviewTask task(String status) {
        ReviewTask task = new ReviewTask();
        task.setId(7L);
        task.setStatus(status);
        task.setOrganization("octo");
        task.setRepository("repo");
        task.setCommitSha("0123456789abcdef0123456789abcdef01234567");
        return task;
    }

    private void verifyNoPersistenceRead() {
        verify(checkRunMapper, never()).selectByGithubCheckRunId(any());
        verify(taskMapper, never()).selectById(any());
    }

    private GithubCheckRunProperties enabledProperties() {
        GithubCheckRunProperties value = new GithubCheckRunProperties();
        value.setEnabled(true);
        return value;
    }

    private String payload() {
        return """
            {
              "action":"rerequested",
              "installation":{"id":77},
              "repository":{"name":"repo","full_name":"octo/repo","owner":{"login":"octo"}},
              "check_run":{"id":99,"name":"RepoGuard PR Review","external_id":"repoguard-task:7:run:1","head_sha":"0123456789abcdef0123456789abcdef01234567"}
            }
            """;
    }
}
