package com.repoguard.agent.review.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.entity.ReviewExecutionAttempt;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewExecutionAttemptMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.ReviewFindingIdentity;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReviewFindingComparisonServiceTest {

    private final ReviewTaskMapper taskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final ReviewExecutionAttemptMapper attemptMapper = org.mockito.Mockito.mock(ReviewExecutionAttemptMapper.class);
    private final ReviewFindingMapper findingMapper = org.mockito.Mockito.mock(ReviewFindingMapper.class);
    private final ReviewFindingComparisonService service = new ReviewFindingComparisonService(
        taskMapper, attemptMapper, findingMapper
    );

    private final ReviewTask task = task();

    @BeforeEach
    void setUp() {
        when(taskMapper.selectById(42L)).thenReturn(task);
        when(findingMapper.updateComparison(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyLong()
        )).thenReturn(1);
    }

    @Test
    void firstSuccessfulAttemptIsNewAndIsPaged() {
        ReviewExecutionAttempt candidate = attempt(102L, 2, "COMPLETED", "prompt-v1");
        ReviewFinding finding = finding(202L, 102L, "src/App.java", 30, "Use logger");
        prepare(candidate, null, List.of(candidate), List.of(finding));

        var result = service.compare(42L, null, 102L, 1, 1);

        assertThat(result.comparable()).isTrue();
        assertThat(result.baselineAttemptId()).isNull();
        assertThat(result.comparabilityReason()).isEqualTo("NO_PREVIOUS_SUCCESSFUL_ATTEMPT");
        assertThat(result.summary().newCount()).isEqualTo(1);
        assertThat(result.findings().items()).extracting(item -> item.status()).containsExactly("NEW");
        assertThat(result.findings().hasMore()).isFalse();
        assertThat(finding.getComparisonAttemptId()).isEqualTo(102L);
    }

    @Test
    void stableFingerprintSurvivesLineShiftAndIsPersisting() {
        ReviewExecutionAttempt baseline = attempt(101L, 1, "COMPLETED", "prompt-v1");
        ReviewExecutionAttempt candidate = attempt(102L, 2, "COMPLETED", "prompt-v1");
        ReviewFinding oldFinding = finding(201L, 101L, "src/App.java", 10, "Use logger on line 10");
        ReviewFinding newFinding = finding(202L, 102L, "./src/App.java", 44, "Use logger on line 44");
        prepare(candidate, baseline, List.of(baseline, candidate), List.of(oldFinding, newFinding));

        var result = service.compare(42L, null, 102L, 1, 10);

        assertThat(result.comparable()).isTrue();
        assertThat(result.baselineAttemptId()).isEqualTo(101L);
        assertThat(result.summary().persistingCount()).isEqualTo(1);
        assertThat(result.findings().items().getFirst().baselineFindingId()).isEqualTo(201L);
        assertThat(newFinding.getComparisonStatus()).isEqualTo("PERSISTING");
    }

    @Test
    void missingBaselineFindingIsResolvedAndLaterReappearanceIsRegressed() {
        ReviewExecutionAttempt first = attempt(101L, 1, "COMPLETED", "prompt-v1");
        ReviewExecutionAttempt second = attempt(102L, 2, "COMPLETED", "prompt-v1");
        ReviewExecutionAttempt third = attempt(103L, 3, "COMPLETED", "prompt-v1");
        ReviewFinding original = finding(201L, 101L, "src/App.java", 10, "Use logger");
        prepare(second, first, List.of(first, second), List.of(original));

        var resolved = service.compare(42L, null, 102L, 1, 10);
        assertThat(resolved.summary().resolvedCount()).isEqualTo(1);
        assertThat(original.getComparisonStatus()).isEqualTo("RESOLVED");

        ReviewFinding reappeared = finding(203L, 103L, "src/App.java", 12, "Use logger");
        prepare(third, second, List.of(first, second, third), List.of(original, reappeared));

        var regressed = service.compare(42L, null, 103L, 1, 10);

        assertThat(regressed.summary().regressedCount()).isEqualTo(1);
        assertThat(regressed.findings().items()).anyMatch(item ->
            "REGRESSED".equals(item.status()) && item.baselineFindingId().equals(201L));
    }

    @Test
    void strategyChangeAndCrossFileMoveAreNotPresentedAsCodeFixes() {
        ReviewExecutionAttempt baseline = attempt(101L, 1, "COMPLETED", "prompt-v1");
        ReviewExecutionAttempt candidate = attempt(102L, 2, "COMPLETED", "prompt-v2");
        ReviewFinding oldFinding = finding(201L, 101L, "src/App.java", 10, "Use logger");
        ReviewFinding newFinding = finding(202L, 102L, "src/Other.java", 10, "Use logger");
        prepare(candidate, baseline, List.of(baseline, candidate), List.of(oldFinding, newFinding));

        var result = service.compare(42L, null, 102L, 1, 10);

        assertThat(result.comparable()).isFalse();
        assertThat(result.comparabilityReason()).isEqualTo("STRATEGY_VERSION_CHANGED");
        assertThat(result.summary().unmatchedCount()).isEqualTo(1);

        candidate.setPromptVersion("prompt-v1");
        when(findingMapper.selectByTaskIdForComparison(42L)).thenReturn(List.of(oldFinding, newFinding));
        var crossFile = service.compare(42L, 101L, 102L, 1, 10);
        assertThat(crossFile.comparable()).isTrue();
        assertThat(crossFile.summary().unmatchedCount()).isEqualTo(1);
        assertThat(crossFile.findings().items().getFirst().reason())
            .isEqualTo("LOCATION_CHANGED_OR_CROSS_FILE");
    }

    @Test
    void rejectsFailedCandidateAndInvalidPage() {
        ReviewExecutionAttempt failed = attempt(102L, 2, "FAILED", "prompt-v1");
        prepare(failed, null, List.of(failed), List.of());

        assertThatThrownBy(() -> service.compare(42L, null, 102L, 1, 10))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("completed or partial");
        assertThatThrownBy(() -> service.compare(42L, null, 102L, 0, 10))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Invalid finding comparison page");
    }

    private void prepare(
        ReviewExecutionAttempt candidate,
        ReviewExecutionAttempt baseline,
        List<ReviewExecutionAttempt> attempts,
        List<ReviewFinding> findings
    ) {
        when(attemptMapper.selectById(candidate.getId())).thenReturn(candidate);
        if (baseline != null) {
            when(attemptMapper.selectById(baseline.getId())).thenReturn(baseline);
        }
        when(attemptMapper.selectByTaskId(42L, 100)).thenReturn(attempts);
        when(findingMapper.selectByTaskIdForComparison(42L)).thenReturn(findings);
    }

    private ReviewTask task() {
        ReviewTask value = new ReviewTask();
        value.setId(42L);
        return value;
    }

    private ReviewExecutionAttempt attempt(Long id, int number, String status, String prompt) {
        ReviewExecutionAttempt value = new ReviewExecutionAttempt();
        value.setId(id);
        value.setTaskId(42L);
        value.setAttemptNo(number);
        value.setStatus(status);
        value.setPromptVersion(prompt);
        value.setContextVersion("context-v1");
        value.setSchemaVersion("schema-v1");
        value.setVerifierVersion("verifier-v1");
        value.setAggregationVersion("aggregation-v1");
        value.setCommitSha("commit-" + number);
        return value;
    }

    private ReviewFinding finding(Long id, Long attemptId, String path, int line, String message) {
        ReviewFinding value = new ReviewFinding();
        value.setId(id);
        value.setTaskId(42L);
        value.setAttemptId(attemptId);
        value.setCategory("FINDING");
        value.setSource("RULE");
        value.setRuleId("RG-001");
        value.setIssueType("OBSERVABILITY");
        value.setFilePath(path);
        value.setLineNumber(line);
        value.setMessage(message);
        value.setAnchorType("ADDED_LINE");
        value.setDetectorVersion("detector-v1");
        value.setRuleConfigVersion(1L);
        value.setSeverity("HIGH");
        value.setRecommendation("Use logger");
        value.setFindingFingerprint(ReviewFindingIdentity.fingerprint(42L, value));
        return value;
    }
}
