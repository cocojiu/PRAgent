package com.repoguard.agent.github.checks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class GithubCheckRunOutcomeResolverTest {

    private final ReviewFindingMapper findingMapper = mock(ReviewFindingMapper.class);
    private final GithubCheckRunProperties properties = new GithubCheckRunProperties();
    private final GithubCheckRunOutcomeResolver resolver = new GithubCheckRunOutcomeResolver(findingMapper, properties);

    @Test
    void mapsBlockingFindingsToFailureAnnotations() {
        ReviewTask task = task("COMPLETED");
        ReviewFinding finding = new ReviewFinding();
        finding.setFilePath("src/Foo.java");
        finding.setLineNumber(12);
        finding.setRuleId("RG-SEC-001");
        finding.setMessage("不要记录密钥");
        finding.setRecommendation("请改用脱敏日志");
        when(findingMapper.selectGithubCheckRunBlockingFindings(7L)).thenReturn(List.of(finding));

        GithubCheckRunOutcomeResolver.Outcome outcome = resolver.resolve(task);

        assertThat(outcome.conclusion()).isEqualTo("failure");
        assertThat(outcome.blockerCount()).isEqualTo(1);
        assertThat(outcome.annotations()).singleElement().satisfies(annotation -> {
            assertThat(annotation.path()).isEqualTo("src/Foo.java");
            assertThat(annotation.startLine()).isEqualTo(12);
            assertThat(annotation.annotationLevel()).isEqualTo("failure");
        });
    }

    @Test
    void pendingHumanReviewTakesActionRequiredConclusion() {
        ReviewTask task = task("PENDING_HUMAN_REVIEW");
        task.setHumanReviewStatus("PENDING");
        when(findingMapper.selectGithubCheckRunBlockingFindings(7L)).thenReturn(List.of());

        assertThat(resolver.resolve(task).conclusion()).isEqualTo("action_required");
    }

    @Test
    void skipsUnanchoredBlockersButReportsThemInSummary() {
        ReviewTask task = task("APPROVED");
        ReviewFinding finding = new ReviewFinding();
        finding.setMessage("缺少可定位行");
        when(findingMapper.selectGithubCheckRunBlockingFindings(7L)).thenReturn(List.of(finding));

        GithubCheckRunOutcomeResolver.Outcome outcome = resolver.resolve(task);

        assertThat(outcome.conclusion()).isEqualTo("failure");
        assertThat(outcome.annotations()).isEmpty();
        assertThat(outcome.summary()).contains("缺少有效代码定位");
    }

    @Test
    void mapsTerminalStatusesAndNeutralRunningStatus() {
        when(findingMapper.selectGithubCheckRunBlockingFindings(7L)).thenReturn(List.of());

        assertThat(resolver.resolve(task("SUPERSEDED")).conclusion()).isEqualTo("cancelled");
        assertThat(resolver.resolve(task("FAILED")).conclusion()).isEqualTo("failure");
        assertThat(resolver.resolve(task("REJECTED")).conclusion()).isEqualTo("failure");
        assertThat(resolver.resolve(task("CHANGES_REQUESTED")).conclusion()).isEqualTo("failure");
        assertThat(resolver.resolve(task("COMPLETED")).conclusion()).isEqualTo("success");
        assertThat(resolver.resolve(task("APPROVED")).conclusion()).isEqualTo("success");
        assertThat(resolver.resolve(task("REVIEWING")).conclusion()).isEqualTo("neutral");
    }

    @Test
    void limitsAnnotationsAndCleansMissingOrOversizedFindingText() {
        properties.setAnnotationLimit(1);
        ReviewFinding first = finding("  src/One.java  ", 2, "  RG-ONE  ", "line1\nline2", "  use a safer API  ");
        ReviewFinding second = finding("src/Two.java", 3, "RG-TWO", "second", "second recommendation");
        ReviewFinding unanchored = finding(" ", 0, null, null, null);
        when(findingMapper.selectGithubCheckRunBlockingFindings(7L)).thenReturn(List.of(first, second, unanchored));

        GithubCheckRunOutcomeResolver.Outcome outcome = resolver.resolve(task("COMPLETED"));

        assertThat(outcome.conclusion()).isEqualTo("failure");
        assertThat(outcome.blockerCount()).isEqualTo(3);
        assertThat(outcome.annotations()).hasSize(1);
        assertThat(outcome.annotations().getFirst().path()).isEqualTo("src/One.java");
        assertThat(outcome.annotations().getFirst().message()).isEqualTo("line1 line2");
        assertThat(outcome.annotations().getFirst().title()).isEqualTo("RG-ONE");
        assertThat(outcome.annotations().getFirst().rawDetails()).isEqualTo("use a safer API");
        assertThat(outcome.summary()).contains("2 个问题缺少有效代码定位");
    }

    @Test
    void truncatesLongFindingFieldsAndUsesFallbacks() {
        String longValue = "x".repeat(520);
        ReviewFinding finding = finding("src/Long.java", 2_147_483_647, " ", longValue, " ");
        when(findingMapper.selectGithubCheckRunBlockingFindings(7L)).thenReturn(List.of(finding));

        GithubCheckRunOutcomeResolver.Outcome outcome = resolver.resolve(task("APPROVED"));

        assertThat(outcome.annotations()).singleElement().satisfies(annotation -> {
            assertThat(annotation.startLine()).isEqualTo(2_147_483_646);
            assertThat(annotation.title()).isEqualTo("RepoGuard blocking finding");
            assertThat(annotation.message()).hasSize(500).endsWith("...");
            assertThat(annotation.rawDetails()).isNull();
        });
    }

    private ReviewFinding finding(String path, Integer line, String rule, String message, String recommendation) {
        ReviewFinding finding = new ReviewFinding();
        finding.setFilePath(path);
        finding.setLineNumber(line);
        finding.setRuleId(rule);
        finding.setMessage(message);
        finding.setRecommendation(recommendation);
        return finding;
    }

    private ReviewTask task(String status) {
        ReviewTask task = new ReviewTask();
        task.setId(7L);
        task.setStatus(status);
        task.setHumanReviewRequired(false);
        task.setHumanReviewStatus("NOT_REQUIRED");
        return task;
    }
}
