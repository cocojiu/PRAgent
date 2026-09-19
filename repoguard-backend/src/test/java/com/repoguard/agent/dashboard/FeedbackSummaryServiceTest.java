package com.repoguard.agent.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.repoguard.agent.dashboard.FeedbackSummaryService.Observation;
import com.repoguard.agent.tenancy.TenantContext;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class FeedbackSummaryServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 17, 12, 0);

    @Test
    void separatesSourcesAndDoesNotTreatIgnoredAsFixedOrFalsePositive() {
        var result = summarize(List.of(row(1, "RULE", "false_positive"), row(2, "LLM", "fixed"),
            row(3, "LLM", "ignored"), row(4, "RULE", "valid")));
        assertThat(result.sampleSize()).isEqualTo(4);
        assertThat(result.sources().getFirst().falsePositive()).isEqualTo(1);
        assertThat(result.sources().getFirst().fixed()).isZero();
        assertThat(result.sources().getLast().fixed()).isEqualTo(1);
        assertThat(result.sources().getLast().ignored()).isEqualTo(1);
        assertThat(result.sources()).allMatch(source -> source.evidenceStatus().equals("INSUFFICIENT_DATA"));
        assertThat(result.details()).extracting(FeedbackSummaryService.FeedbackDetail::findingId).containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    void excludesEvaluationStaleMissingIdentityAndDuplicateFeedback() {
        Observation good = row(1, "RULE", "valid");
        var result = summarize(List.of(good, good,
            new Observation(2, 1, 1, 2, true, "FINDING", "RULE", "fp2", "fixed", "alice", NOW, "org", "repo", 3, good.headSha(), "GITHUB_WEBHOOK"),
            new Observation(3, 1, 1, 1, true, "FINDING", "LLM", "fp3", "valid", "alice", NOW, "org", "repo", 3, good.headSha(), "EVALUATION"),
            new Observation(4, 1, 1, 1, true, "FINDING", "RULE", "fp4", "valid", "unknown", NOW, "org", "repo", 3, good.headSha(), "GITHUB_WEBHOOK"),
            new Observation(5, 1, 1, 1, true, "FINDING", "RULE", null, "valid", "alice", NOW, "org", "repo", 3, good.headSha(), "GITHUB_WEBHOOK"),
            row(6, "SARIF", "valid"), row(7, "RULE", "unreviewed")));
        assertThat(result.sampleSize()).isEqualTo(1);
        assertThat(result.excludedOrDuplicateCount()).isEqualTo(7);
    }

    @Test
    void boundsObservationsAndDetailsAndNeverReturnsPercentageEstimates() {
        List<Observation> rows = new ArrayList<>();
        for (int i = 0; i < 1001; i++) rows.add(row(i + 1, "RULE", "fixed"));
        var result = summarize(rows);
        assertThat(result.truncated()).isTrue();
        assertThat(result.examinedCount()).isEqualTo(1000);
        assertThat(result.sampleSize()).isEqualTo(1000);
        assertThat(result.details()).hasSize(20);
        assertThat(result.sources().getFirst().evidenceStatus()).isEqualTo("COUNTS_ONLY");
        assertThat(result.sources().getLast().evidenceStatus()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(summarize(List.of()).sampleSize()).isZero();
    }

    @Test
    void loadsOnlyCurrentTenantWithBoundedTimeWindowAndDeterministicOrdering() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Observation>>any(), any(Object[].class))).thenReturn(List.of());
        try (TenantContext.Scope _ = TenantContext.withTenant(42L)) {
            assertThat(new FeedbackSummaryService(jdbc).summary().sampleSize()).isZero();
        }
        var values = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(argThat(sql -> sql.contains("f.tenant_id = ?")
            && sql.contains("t.tenant_id = f.tenant_id") && sql.contains("f.feedback_at >= ?")
            && sql.contains("order by f.feedback_at desc, f.id desc limit 1001")), org.mockito.ArgumentMatchers.<RowMapper<Observation>>any(),
            values.capture());
        assertThat(values.getValue()).hasSize(3).startsWith(42L);
        assertThat(((LocalDateTime) values.getValue()[1]).plusDays(30)).isEqualTo(values.getValue()[2]);
    }

    private FeedbackSummaryService.FeedbackSummary summarize(List<Observation> rows) {
        return FeedbackSummaryService.summarize(rows, NOW.minusDays(30), NOW);
    }
    private Observation row(long id, String source, String status) {
        return new Observation(id, 1, 1, 1, true, "FINDING", source, "fingerprint-" + id, status,
            "alice", NOW, "org", "repo", 3, "a".repeat(40), "GITHUB_WEBHOOK");
    }
}
