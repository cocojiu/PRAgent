package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.dto.RepositorySuppressionRequest;
import com.repoguard.agent.dto.RepositorySuppressionResponse;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class RepositorySuppressionServiceTest {

    private final RepositorySuppressionRepository repository = mock(RepositorySuppressionRepository.class);
    private final ReviewFindingMapper findingMapper = mock(ReviewFindingMapper.class);
    private final ReviewRuleRegistry ruleRegistry = mock(ReviewRuleRegistry.class);
    private final RepositorySuppressionService service = new RepositorySuppressionService(
        repository, findingMapper, ruleRegistry
    );

    @Test
    void createsScopedProposalWithReplayCountAndMapsStoredResponse() {
        when(ruleRegistry.contains("RG-AUTH-001")).thenReturn(true);
        ReviewFinding matching = new ReviewFinding();
        matching.setFilePath("src/Auth.java");
        matching.setMessage("unsafe auth token");
        ReviewFinding unrelated = new ReviewFinding();
        unrelated.setFilePath("src/Other.java");
        List<ReviewFinding> replayFindings = new java.util.ArrayList<>();
        replayFindings.add(matching);
        replayFindings.add(unrelated);
        replayFindings.add(null);
        when(findingMapper.selectRecentSuppressionHits("octocat", "repo", "RG-AUTH-001", 100))
            .thenReturn(replayFindings);
        RepositorySuppressionRepository.StoredSuppression stored = stored("PROPOSED", "src/**");
        when(repository.insert(
            eq(1L), eq("octocat"), eq("repo"), eq("RG-AUTH-001"), eq("src/**"), eq("token"),
            eq("accepted legacy code"), eq("alice"), any(LocalDateTime.class), eq(1)
        )).thenReturn(stored);

        RepositorySuppressionResponse response = service.create(new RepositorySuppressionRequest(
            " octocat ", "repo", "rg-auth-001", "src/**", "token", "accepted legacy code",
            OffsetDateTime.now().plusDays(30).toString()
        ), "alice");

        assertThat(response.id()).isEqualTo(9L);
        assertThat(response.status()).isEqualTo("PROPOSED");
        assertThat(response.previewHitCount()).isEqualTo(2);
        verify(repository).insert(
            eq(1L), eq("octocat"), eq("repo"), eq("RG-AUTH-001"), eq("src/**"), eq("token"),
            eq("accepted legacy code"), eq("alice"), any(LocalDateTime.class), eq(1)
        );
    }

    @Test
    void createsProposalFromFalsePositiveAndSupportsLifecycle() {
        when(ruleRegistry.contains("RG-AUTH-001")).thenReturn(true);
        when(findingMapper.selectRecentSuppressionHits(any(), any(), any(), anyInt())).thenReturn(List.of());
        RepositorySuppressionRepository.StoredSuppression proposed = stored("PROPOSED", "src/Auth.java");
        RepositorySuppressionRepository.StoredSuppression active = stored("ACTIVE", "src/Auth.java");
        RepositorySuppressionRepository.StoredSuppression revoked = stored("REVOKED", "src/Auth.java");
        when(repository.insert(anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
            .thenReturn(proposed);
        when(repository.find(1L, 9L)).thenReturn(proposed, active);
        when(repository.transition(eq(1L), eq(9L), eq("PROPOSED"), eq("ACTIVE"), any(), any()))
            .thenReturn(active);
        when(repository.transition(eq(1L), eq(9L), eq("ACTIVE"), eq("REVOKED"), any(), any()))
            .thenReturn(revoked);
        ReviewTask task = new ReviewTask();
        task.setOrganization("octocat");
        task.setRepository("repo");
        ReviewFinding finding = new ReviewFinding();
        finding.setRuleId("rg-auth-001");
        finding.setFilePath("src/Auth.java");

        assertThat(service.createFromFinding(task, finding, null, "known fixture").status())
            .isEqualTo("PROPOSED");
        assertThat(service.createFromFinding(null, finding, "alice", null)).isNull();
        assertThat(service.activate(9L, "alice", "approved").status()).isEqualTo("ACTIVE");
        assertThat(service.revoke(9L, "alice", "fixed").status()).isEqualTo("REVOKED");
    }

    @Test
    void listsReferencesExpiresDueAndRejectsInvalidTransitions() {
        RepositorySuppressionRepository.StoredSuppression active = stored("ACTIVE", "src/**");
        when(repository.list(1L, "octocat", "repo", 50)).thenReturn(List.of(active));
        when(repository.activeFor(1L, "octocat", "repo")).thenReturn(List.of(active));
        when(repository.expireDue(1L, 100)).thenReturn(2);

        assertThat(service.list("octocat", "repo", 50)).hasSize(1);
        assertThat(service.activeReferences("octocat", "repo")).singleElement()
            .satisfies(reference -> assertThat(reference.fileGlob()).isEqualTo("src/**"));
        assertThat(service.expireDue(100)).isEqualTo(2);
        when(repository.find(1L, 404L)).thenReturn(null);
        assertThatThrownBy(() -> service.activate(404L, "alice", null))
            .hasMessageContaining("not found");
        when(repository.find(1L, 9L)).thenReturn(active);
        assertThatThrownBy(() -> service.activate(9L, "alice", null))
            .hasMessageContaining("no longer PROPOSED");
    }

    @Test
    void rejectsUnknownRulesBroadScopesAndInvalidExpiry() {
        when(ruleRegistry.contains(any())).thenReturn(false);
        RepositorySuppressionRequest base = new RepositorySuppressionRequest(
            "octocat", "repo", "RG-UNKNOWN", "src/**", null, "reason",
            OffsetDateTime.now().plusDays(10).toString()
        );
        assertThatThrownBy(() -> service.create(base, "alice")).hasMessageContaining("Unknown review rule");
        when(ruleRegistry.contains("RG-AUTH-001")).thenReturn(true);
        assertThatThrownBy(() -> service.create(new RepositorySuppressionRequest(
            "octocat", "repo", "RG-AUTH-001", "**", null, "reason",
            OffsetDateTime.now().plusDays(10).toString()
        ), "alice")).hasMessageContaining("too broad");
        assertThatThrownBy(() -> service.create(new RepositorySuppressionRequest(
            "octocat", "repo", "RG-AUTH-001", null, null, "reason",
            OffsetDateTime.now().plusDays(10).toString()
        ), "alice")).hasMessageContaining("requires fileGlob or symbol");
        assertThatThrownBy(() -> service.create(new RepositorySuppressionRequest(
            "octocat", "repo", "RG-AUTH-001", "src/**", null, "reason", "not-a-date"
        ), "alice")).hasMessageContaining("ISO-8601");
        assertThatThrownBy(() -> service.create(new RepositorySuppressionRequest(
            "octocat", "repo", "RG-AUTH-001", "src/**", null, "reason",
            OffsetDateTime.now().minusDays(1).toString()
        ), "alice")).hasMessageContaining("in the future");
    }

    private RepositorySuppressionRepository.StoredSuppression stored(String status, String fileGlob) {
        LocalDateTime now = LocalDateTime.now();
        return new RepositorySuppressionRepository.StoredSuppression(
            9L, 1L, "octocat", "repo", "RG-AUTH-001", fileGlob, "token", "reason", status,
            "alice", now.plusDays(30), 2, 1, now, now, now
        );
    }
}
