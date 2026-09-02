package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.PullRequestDiff;
import com.repoguard.agent.scm.ScmCommentDraft;
import com.repoguard.agent.scm.ScmCommentResult;
import com.repoguard.agent.scm.ScmProvider;
import com.repoguard.agent.scm.ScmProviderRegistry;
import com.repoguard.agent.scm.ScmStatusRequest;
import com.repoguard.agent.scm.ScmStatusResult;
import com.repoguard.agent.scm.ScmProviderServiceImpl;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScmProviderServiceImplTest {

    private final ScmProviderRegistry registry = mock(ScmProviderRegistry.class);
    private final ReviewTaskMapper taskMapper = mock(ReviewTaskMapper.class);
    private final ScmProvider provider = mock(ScmProvider.class);
    private final ScmProviderServiceImpl service = new ScmProviderServiceImpl(registry, taskMapper);
    private final ReviewTask task = task();

    @Test
    void delegatesProviderOperationsAfterLoadingTask() {
        when(registry.require("gitlab")).thenReturn(provider);
        when(taskMapper.selectById(42L)).thenReturn(task);
        PullRequestDiff diff = new PullRequestDiff("acme", "widgets", 7, "sha", List.of());
        when(provider.fetchPullRequestDiff(task)).thenReturn(diff);
        when(provider.fetchPullRequestHeadSha(task)).thenReturn("sha");
        ScmCommentDraft draft = new ScmCommentDraft(1L, null, null, "note");
        ScmCommentResult comment = new ScmCommentResult("GITLAB", 1L, true, "PUBLISHED", "ok", null, 2L);
        when(provider.publishComment(task, draft)).thenReturn(comment);
        ScmStatusRequest request = new ScmStatusRequest("RepoGuard", "success", null, null);
        ScmStatusResult status = new ScmStatusResult("GITLAB", true, "success", "ok", null);
        when(provider.publishStatus(task, request)).thenReturn(status);

        assertThat(service.diff("gitlab", 42L)).isEqualTo(diff);
        assertThat(service.head("gitlab", 42L)).containsEntry("sha", "sha");
        assertThat(service.comment("gitlab", 42L, draft)).isEqualTo(comment);
        assertThat(service.status("gitlab", 42L, request)).isEqualTo(status);
        verify(provider).fetchPullRequestDiff(task);
        verify(provider).publishComment(task, draft);
    }

    @Test
    void reportsMissingTaskAtApplicationBoundary() {
        when(taskMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.diff("gitlab", 99L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Review task not found");
    }

    private ReviewTask task() {
        ReviewTask value = new ReviewTask();
        value.setId(42L);
        value.setOrganization("acme");
        value.setRepository("widgets");
        value.setPrNumber(7);
        value.setCommitSha("sha");
        return value;
    }
}
