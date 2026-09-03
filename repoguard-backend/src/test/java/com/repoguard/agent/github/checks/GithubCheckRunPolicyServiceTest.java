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

        assertThat(service.isEnabled(null)).isFalse();
        ReviewTask incomplete = new ReviewTask();
        incomplete.setOrganization(" ");
        incomplete.setRepository("repo");
        assertThat(service.isEnabled(incomplete)).isFalse();

        GithubCheckRunPolicy disabled = new GithubCheckRunPolicy();
        disabled.setEnabled(false);
        when(mapper.selectByRepository("octo", "repo")).thenReturn(disabled);
        assertThat(service.isEnabled(task)).isFalse();

        disabled.setEnabled(true);
        assertThat(service.isEnabled(task)).isTrue();
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

    @Test
    void rejectsInvalidVersionsNamesAndCompareAndSetRaces() {
        assertThatThrownBy(() -> service.setEnabled("octo", "repo", true, -1, "admin"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("negative");
        assertThatThrownBy(() -> service.find("octo/repo", "repo"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("invalid format");
        assertThatThrownBy(() -> service.find("octo", "repo\\nested"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("invalid format");

        when(mapper.selectByRepository("octo", "repo")).thenReturn(null);
        assertThatThrownBy(() -> service.setEnabled("octo", "repo", true, 2, "admin"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("reload");

        GithubCheckRunPolicy current = new GithubCheckRunPolicy();
        current.setId(10L);
        current.setPolicyVersion(4L);
        when(mapper.selectByRepository("octo", "repo")).thenReturn(current);
        when(mapper.updateEnabled(eq(10L), eq(true), eq(4L), eq("unknown"), any())).thenReturn(0);
        assertThatThrownBy(() -> service.setEnabled("octo", "repo", true, 4, " "))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("reload");
    }
}
