package com.repoguard.agent.github.checks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.entity.GithubCheckRunPolicy;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.GithubCheckRunPolicyMapper;
import org.junit.jupiter.api.Test;

class GithubCheckRunPolicyServiceTest {

    private final GithubCheckRunPolicyMapper mapper = mock(GithubCheckRunPolicyMapper.class);
    private final GithubCheckRunPolicyService service = new GithubCheckRunPolicyService(mapper);

    @Test
    void absenceIsFailClosedAndExplicitEnableIsRequired() {
        when(mapper.selectByRepository("octo", "repo")).thenReturn(null);
        ReviewTask task = new ReviewTask();
        task.setOrganization("octo");
        task.setRepository("repo");
        assertThat(service.isEnabled(task)).isFalse();
    }

    @Test
    void setEnabledUsesCreateVersionZeroAndCasForSubsequentChanges() {
        GithubCheckRunPolicy current = new GithubCheckRunPolicy();
        current.setId(9L);
        current.setPolicyVersion(1L);
        current.setEnabled(true);
        when(mapper.selectByRepository("octo", "repo")).thenReturn(null, current);
        GithubCheckRunPolicy created = service.setEnabled("octo", "repo", true, 0, "admin");
        assertThat(created.getPolicyVersion()).isEqualTo(1L);
        assertThat(created.getEnabled()).isTrue();
        verify(mapper).insert(any(GithubCheckRunPolicy.class));

        when(mapper.updateEnabled(eq(9L), eq(false), eq(1L), eq("admin"), any())).thenReturn(1);
        GithubCheckRunPolicy updated = service.setEnabled("octo", "repo", false, 1, "admin");
        assertThat(updated.getPolicyVersion()).isEqualTo(2L);
        assertThat(updated.getEnabled()).isFalse();
    }

    @Test
    void staleVersionIsRejected() {
        GithubCheckRunPolicy current = new GithubCheckRunPolicy();
        current.setPolicyVersion(3L);
        when(mapper.selectByRepository("octo", "repo")).thenReturn(current);

        assertThatThrownBy(() -> service.setEnabled("octo", "repo", true, 2, "admin"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("reload");
    }
}
