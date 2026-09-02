package com.repoguard.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.review.PullRequestDiff;
import com.repoguard.agent.scm.ScmChangeRequestSummary;
import com.repoguard.agent.scm.ScmCommentDraft;
import com.repoguard.agent.scm.ScmCommentResult;
import com.repoguard.agent.scm.ScmProviderRegistry;
import com.repoguard.agent.scm.ScmStatusRequest;
import com.repoguard.agent.scm.ScmStatusResult;
import com.repoguard.agent.service.ScmProviderService;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScmProviderControllerTest {

    private final ScmProviderService service = mock(ScmProviderService.class);
    private final ScmProviderController controller = new ScmProviderController(service);

    @Test
    void exposesProviderNeutralReadAndWriteOperations() {
        List<ScmProviderRegistry.ScmProviderDescriptor> descriptors = List.of(
            new ScmProviderRegistry.ScmProviderDescriptor("GITLAB", true, "acme/widgets")
        );
        List<ScmChangeRequestSummary> changes = List.of(
            new ScmChangeRequestSummary("GITLAB", "acme", "widgets", 7, "Improve validation",
                "feature/validation", "head-sha", "octocat", "https://gitlab.example/mr/7", "2026-09-01")
        );
        PullRequestDiff diff = new PullRequestDiff("acme", "widgets", 7, "head-sha", List.of());
        ScmCommentResult comment = new ScmCommentResult("GITLAB", 9L, true, "PUBLISHED", "ok", null, 12L);
        ScmStatusResult status = new ScmStatusResult("GITLAB", true, "success", "ok", null);
        when(service.providers()).thenReturn(descriptors);
        when(service.changeRequests("gitlab")).thenReturn(changes);
        when(service.diff("gitlab", 42L)).thenReturn(diff);
        when(service.head("gitlab", 42L)).thenReturn(java.util.Map.of("provider", "GITLAB", "sha", "head-sha"));
        when(service.comment("gitlab", 42L, new ScmCommentDraft(9L, "src/App.java", 4, "ok"))).thenReturn(comment);
        when(service.status("gitlab", 42L, new ScmStatusRequest("RepoGuard", "success", "ok", null))).thenReturn(status);

        assertThat(controller.providers().data()).isEqualTo(descriptors);
        assertThat(controller.changeRequests("gitlab").data()).isEqualTo(changes);
        assertThat(controller.diff("gitlab", 42L).data()).isEqualTo(diff);
        assertThat(controller.head("gitlab", 42L).data()).containsEntry("sha", "head-sha");
        assertThat(controller.comment("gitlab", 42L,
            new ScmCommentDraft(9L, "src/App.java", 4, "ok")).data()).isEqualTo(comment);
        assertThat(controller.status("gitlab", 42L,
            new ScmStatusRequest("RepoGuard", "success", "ok", null)).data()).isEqualTo(status);
        verify(service).diff("gitlab", 42L);
        verify(service).comment("gitlab", 42L, new ScmCommentDraft(9L, "src/App.java", 4, "ok"));
    }
}
