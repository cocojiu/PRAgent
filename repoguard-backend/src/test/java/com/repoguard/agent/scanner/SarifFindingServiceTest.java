package com.repoguard.agent.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.dto.SarifImportRequest;
import com.repoguard.agent.entity.ReviewExecutionAttempt;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewExecutionAttemptMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper.SarifImportBatchRow;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SarifFindingServiceTest {

    private final ReviewTaskMapper taskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final ReviewFindingMapper findingMapper = org.mockito.Mockito.mock(ReviewFindingMapper.class);
    private final ReviewExecutionAttemptMapper attemptMapper = org.mockito.Mockito.mock(ReviewExecutionAttemptMapper.class);
    private final SarifFindingService service = new SarifFindingService(
        new ObjectMapper(),
        taskMapper,
        findingMapper,
        attemptMapper
    );

    @BeforeEach
    void defaults() {
        when(attemptMapper.selectById(17L)).thenReturn(currentAttempt());
        when(findingMapper.selectSarifImportBatch(any(), any(), any(), any(), any(), any())).thenReturn(null);
        when(findingMapper.selectActiveSarifImportBatches(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        org.mockito.Mockito.doAnswer(invocation -> {
            SarifImportBatchRow batch = invocation.getArgument(0);
            batch.setId(101L);
            return 1;
        }).when(findingMapper).insertSarifImportBatch(any(SarifImportBatchRow.class));
    }

    @Test
    void importsSarifResultIntoTenantScopedFindingAndSkipsUnsafeLocations() {
        ReviewTask task = currentTask();
        when(taskMapper.selectById(9L)).thenReturn(task);
        String sarif = """
            {
              "version":"2.1.0",
              "runs":[{"tool":{"driver":{"rules":[{"id":"SAST-1","help":{"text":"Use a safe API"}}]}},
                "results":[
                  {"ruleId":"SAST-1","level":"error","message":{"text":"Unsafe call"},"locations":[{"physicalLocation":{"artifactLocation":{"uri":"src/App.java"},"region":{"startLine":7}}}]},
                  {"ruleId":"SAST-2","locations":[{"physicalLocation":{"artifactLocation":{"uri":"../../secret.txt"},"region":{"startLine":1}}}]}
                ]}]
            }
            """;

        var result = service.importFindings(9L, new SarifImportRequest(sarif));

        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.ruleId()).isEqualTo("SAST-1");
            assertThat(finding.filePath()).isEqualTo("src/App.java");
            assertThat(finding.severity()).isEqualTo("HIGH");
        });
        verify(findingMapper, never()).markCurrentAttemptHistorical(9L);
        var findingCaptor = org.mockito.ArgumentCaptor.forClass(ReviewFinding.class);
        verify(findingMapper).insert(findingCaptor.capture());
        assertThat(findingCaptor.getValue().getAttemptId()).isEqualTo(17L);
        assertThat(findingCaptor.getValue().getSourceBatchId()).isEqualTo(101L);
    }

    @Test
    void exportsCurrentFindingsAsSarifDocument() {
        when(taskMapper.selectById(9L)).thenReturn(currentTask());
        when(attemptMapper.selectById(17L)).thenReturn(currentAttempt());
        ReviewFinding finding = new ReviewFinding();
        finding.setId(2L);
        finding.setRuleId("SAST-1");
        finding.setFilePath("src/App.java");
        finding.setLineNumber(7);
        finding.setSeverity("HIGH");
        finding.setMessage("Unsafe call");
        finding.setCurrentAttempt(true);
        finding.setCategory("FINDING");
        when(findingMapper.selectList(any())).thenReturn(List.of(finding));

        var document = service.exportFindings(9L);

        assertThat(document.version()).isEqualTo("2.1.0");
        assertThat(document.runs()).hasSize(1);
        assertThat(document.runs().getFirst()).containsKey("results");
        verify(findingMapper, never()).insert(any(ReviewFinding.class));
    }

    @Test
    void rejectsMalformedSarifJson() {
        when(taskMapper.selectById(9L)).thenReturn(currentTask());

        assertThatThrownBy(() -> service.importFindings(9L, new SarifImportRequest("{")))
            .hasMessageContaining("Invalid SARIF JSON");
    }

    @Test
    void rejectsUnsupportedVersionAndEmptyRuns() {
        when(taskMapper.selectById(9L)).thenReturn(currentTask());

        assertThatThrownBy(() -> service.importFindings(9L, new SarifImportRequest(
            "{\"version\":\"1.0\",\"runs\":[]}")))
            .hasMessageContaining("Only SARIF version 2.1.0");
        assertThatThrownBy(() -> service.importFindings(9L, new SarifImportRequest(
            "{\"version\":\"2.1.0\",\"runs\":[]}")))
            .hasMessageContaining("non-empty array");
    }

    @Test
    void skipsMalformedResultsAndUsesRuleHelpFallback() {
        when(taskMapper.selectById(9L)).thenReturn(currentTask());
        String sarif = """
            {
              "version":"2.1.0",
              "runs":[
                {"tool":{"driver":{"rules":[{"id":"RULE-1","shortDescription":{"text":"Use a safe API"}}]}},"results":"ignored"},
                {"tool":{"driver":{"rules":[{"id":"RULE-1","shortDescription":{"text":"Use a safe API"}}]}},"results":[
                  {},
                  {"ruleId":"RULE-1","locations":[]},
                  {"ruleId":"RULE-1","locations":[{"physicalLocation":{"artifactLocation":{"uri":"./src/App.java"},"region":{"startLine":0}}}]},
                  {"ruleId":"RULE-1","message":{},"locations":[{"physicalLocation":{"artifactLocation":{"uri":"./src/App.java"},"region":{"startLine":3}}}]}
                ]}
              ]
            }
            """;

        var result = service.importFindings(9L, new SarifImportRequest(sarif));

        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(3);
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.filePath()).isEqualTo("src/App.java");
            assertThat(finding.message()).isEqualTo("Use a safe API");
        });
    }

    @Test
    void retriesSamePayloadWithoutCreatingDuplicateFindings() {
        when(taskMapper.selectById(9L)).thenReturn(currentTask());
        SarifImportBatchRow existing = new SarifImportBatchRow();
        existing.setId(102L);
        existing.setImportedCount(1);
        existing.setSkippedCount(0);
        when(findingMapper.selectSarifImportBatch(any(), any(), any(), any(), any(), any())).thenReturn(existing);
        ReviewFinding existingFinding = new ReviewFinding();
        existingFinding.setRuleId("SAST-1");
        existingFinding.setFilePath("src/App.java");
        existingFinding.setLineNumber(7);
        existingFinding.setSeverity("HIGH");
        existingFinding.setMessage("Unsafe call");
        when(findingMapper.selectList(any())).thenReturn(List.of(existingFinding));

        var result = service.importFindings(9L, new SarifImportRequest(
            "{\"version\":\"2.1.0\",\"runs\":[{\"tool\":{\"driver\":{\"name\":\"scanner\"}},\"results\":[]}]}"
        ));

        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.findings()).singleElement().satisfies(finding ->
            assertThat(finding.ruleId()).isEqualTo("SAST-1"));
        verify(findingMapper, never()).insertSarifImportBatch(any(SarifImportBatchRow.class));
        verify(findingMapper, never()).markCurrentAttemptHistorical(9L);
    }

    @Test
    void rejectsImportWhenTaskHasNoCurrentAttempt() {
        ReviewTask task = new ReviewTask();
        task.setId(9L);
        task.setCommitSha("abc123");
        when(taskMapper.selectById(9L)).thenReturn(task);

        assertThatThrownBy(() -> service.importFindings(9L, new SarifImportRequest(
            "{\"version\":\"2.1.0\",\"runs\":[]}")))
            .hasMessageContaining("current review execution attempt");
        verify(attemptMapper, never()).selectById(any());
    }

    @Test
    void rejectsMissingTaskAndBlankSarifContent() {
        when(taskMapper.selectById(9L)).thenReturn(null);
        assertThatThrownBy(() -> service.importFindings(9L, new SarifImportRequest("{}")))
            .hasMessageContaining("Review task not found");

        when(taskMapper.selectById(9L)).thenReturn(currentTask());
        assertThatThrownBy(() -> service.importFindings(9L, null))
            .hasMessageContaining("SARIF content is required");
        assertThatThrownBy(() -> service.importFindings(9L, new SarifImportRequest("  ")))
            .hasMessageContaining("SARIF content is required");
    }

    @Test
    void rejectsMismatchedAttemptAndMissingCommitSha() {
        ReviewTask task = currentTask();
        when(taskMapper.selectById(9L)).thenReturn(task);
        ReviewExecutionAttempt mismatched = currentAttempt();
        mismatched.setTaskId(10L);
        when(attemptMapper.selectById(17L)).thenReturn(mismatched);
        assertThatThrownBy(() -> service.importFindings(9L, new SarifImportRequest(
            "{\"version\":\"2.1.0\",\"runs\":[{}]}")))
            .hasMessageContaining("missing or mismatched");

        when(attemptMapper.selectById(17L)).thenReturn(currentAttempt());
        task.setCommitSha(null);
        ReviewExecutionAttempt noCommit = currentAttempt();
        noCommit.setCommitSha(null);
        when(attemptMapper.selectById(17L)).thenReturn(noCommit);
        assertThatThrownBy(() -> service.importFindings(9L, new SarifImportRequest(
            "{\"version\":\"2.1.0\",\"runs\":[{}]}")))
            .hasMessageContaining("attempt commit SHA");
    }

    @Test
    void supersedesPreviousSarifBatchAndLegacyFindings() {
        when(taskMapper.selectById(9L)).thenReturn(currentTask());
        SarifImportBatchRow previous = new SarifImportBatchRow();
        previous.setId(77L);
        when(findingMapper.selectActiveSarifImportBatches(any(), any(), any(), any(), any(), any()))
            .thenReturn(List.of(previous));

        service.importFindings(9L, new SarifImportRequest(
            "{\"version\":\"2.1.0\",\"runs\":[{\"tool\":{\"driver\":{\"name\":\"scanner\"}},\"results\":[]}] }"));

        verify(findingMapper).markSarifImportBatchSuperseded(eq(77L), any(java.time.LocalDateTime.class));
        verify(findingMapper, times(2)).update(any(), any());
    }

    @Test
    void returnsRacedBatchWhenUniqueConstraintWins() {
        when(taskMapper.selectById(9L)).thenReturn(currentTask());
        SarifImportBatchRow raced = new SarifImportBatchRow();
        raced.setId(103L);
        raced.setImportedCount(0);
        raced.setSkippedCount(0);
        when(findingMapper.selectSarifImportBatch(any(), any(), any(), any(), any(), any()))
            .thenReturn(null, raced);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DuplicateKeyException("duplicate"))
            .when(findingMapper).insertSarifImportBatch(any(SarifImportBatchRow.class));

        var result = service.importFindings(9L, new SarifImportRequest(
            "{\"version\":\"2.1.0\",\"runs\":[{\"tool\":{\"driver\":{\"name\":\"scanner\"}},\"results\":[]}] }"));

        assertThat(result.imported()).isZero();
        verify(findingMapper, never()).markSarifImportBatchSuperseded(any(), any());
    }

    @Test
    void rejectsBatchWithoutGeneratedIdentifier() {
        when(taskMapper.selectById(9L)).thenReturn(currentTask());
        org.mockito.Mockito.doAnswer(invocation -> 1)
            .when(findingMapper).insertSarifImportBatch(any(SarifImportBatchRow.class));

        assertThatThrownBy(() -> service.importFindings(9L, new SarifImportRequest(
            "{\"version\":\"2.1.0\",\"runs\":[{\"results\":[]}] }")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("batch id");
    }

    @Test
    void exportsFallbackFieldsAndSeverityLevels() {
        when(taskMapper.selectById(9L)).thenReturn(currentTask());
        ReviewFinding medium = new ReviewFinding();
        medium.setId(4L);
        medium.setFilePath("src/Medium.java");
        medium.setSeverity("MEDIUM");
        medium.setCurrentAttempt(true);
        medium.setCategory("FINDING");
        ReviewFinding low = new ReviewFinding();
        low.setId(5L);
        low.setFilePath("src/Low.java");
        low.setSeverity("LOW");
        low.setMessage("");
        low.setCurrentAttempt(true);
        low.setCategory("FINDING");
        when(findingMapper.selectList(any())).thenReturn(List.of(medium, low));

        var document = service.exportFindings(9L);

        assertThat(document.runs()).singleElement().satisfies(run -> {
            var results = (List<?>) run.get("results");
            assertThat(results).hasSize(2);
            assertThat(results.getFirst().toString()).contains("SARIF-4", "warning");
            assertThat(results.get(1).toString()).contains("SARIF-5", "note", "RepoGuard finding");
        });
    }

    private ReviewTask currentTask() {
        ReviewTask task = new ReviewTask();
        task.setId(9L);
        task.setCurrentAttemptId(17L);
        task.setCommitSha("abc123");
        return task;
    }

    private ReviewExecutionAttempt currentAttempt() {
        ReviewExecutionAttempt attempt = new ReviewExecutionAttempt();
        attempt.setId(17L);
        attempt.setTaskId(9L);
        attempt.setCommitSha("abc123");
        return attempt;
    }
}
