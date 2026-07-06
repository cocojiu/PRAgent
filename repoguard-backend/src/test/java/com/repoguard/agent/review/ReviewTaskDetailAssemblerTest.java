package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.dto.ChangedFileDto;
import com.repoguard.agent.dto.FindingSeverityCountsDto;
import com.repoguard.agent.dto.MissingTestDto;
import com.repoguard.agent.dto.ReviewFindingDto;
import com.repoguard.agent.dto.ReviewTaskListItem;
import com.repoguard.agent.dto.ReviewTimelineItem;
import com.repoguard.agent.entity.ReviewTask;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewTaskDetailAssemblerTest {

    private final ReviewTaskDetailAssembler assembler = new ReviewTaskDetailAssembler(
        new ReviewRiskProfileBuilder(),
        new PrReviewSummaryBuilder()
    );

    @Test
    void assemblesRiskLlmChunkAndHumanReviewDetails() {
        ReviewTask task = new ReviewTask();
        task.setPrUrl("https://github.com/octocat/Hello-World/pull/42");
        task.setMqRetries(2);
        task.setLlmProvider("DASHSCOPE");
        task.setLlmModel("qwen-plus");
        task.setLlmDurationMs(1250);
        task.setLlmParseStatus("PARTIAL_FALLBACK");
        task.setLlmFallbackReason("one chunk failed");
        task.setLlmPromptSummary(
            "chunked=true; chunks=3; aggregateRisk=HIGH; aggregateFindings=2;"
                + " failedChunks=1; chunkReasons=timeout, timeout, parse_error"
        );
        task.setLlmPromptTokens(120);
        task.setLlmCompletionTokens(80);
        task.setLlmTotalTokens(200);
        task.setLlmEstimatedCost(new BigDecimal("0.0123"));

        ReviewTaskListItem item = new ReviewTaskListItem(
            521L,
            42,
            "Review task",
            "Hello-World",
            "octocat",
            "abc123",
            "main",
            "completed",
            "low",
            2,
            "completed",
            "github_pr_picker",
            "github_pr_picker",
            "2026-06-19 10:00:00",
            "0 分 37 秒",
            null,
            null,
            null,
            true,
            "pending",
            "verify security changes",
            "reviewer",
            "2026-06-19 10:05:00"
        );
        List<ReviewFindingDto> findings = List.of(
            new ReviewFindingDto("high", "src/SecurityConfig.java", 20, "permission bypass", "tighten policy")
        );
        List<MissingTestDto> missingTests = List.of(
            new MissingTestDto("src/SecurityConfig.java", "authorize", "unit", "add coverage")
        );
        List<ChangedFileDto> changedFiles = List.of(
            new ChangedFileDto("src/SecurityConfig.java", "modified", 40, 5)
        );
        List<ReviewTimelineItem> timeline = List.of(
            new ReviewTimelineItem("Review completed", "10:00:37", "done")
        );

        var result = assembler.assemble(task, item, findings, missingTests, changedFiles, timeline);

        assertThat(result.riskLevel()).isEqualTo("medium");
        assertThat(result.riskProfile().level()).isEqualTo("medium");
        assertThat(result.prSummary().overallRisk()).isEqualTo("medium");
        assertThat(result.llm().provider()).isEqualTo("dashscope");
        assertThat(result.llm().parseStatus()).isEqualTo("partial_fallback");
        assertThat(result.llm().estimatedCost()).isEqualTo("0.0123");
        assertThat(result.chunkedReview().enabled()).isTrue();
        assertThat(result.chunkedReview().chunkCount()).isEqualTo(3);
        assertThat(result.chunkedReview().aggregateRisk()).isEqualTo("high");
        assertThat(result.chunkedReview().failedChunks()).isEqualTo(1);
        assertThat(result.chunkedReview().reasons()).containsExactly("timeout", "parse_error");
        assertThat(result.rabbitMq().deliveryCount()).isEqualTo(3);
        assertThat(result.rabbitMq().retryCount()).isEqualTo(2);
        assertThat(result.humanReviewRequired()).isTrue();
        assertThat(result.humanReviewStatus()).isEqualTo("pending");
        assertThat(result.findings()).isSameAs(findings);
        assertThat(result.timeline()).isSameAs(timeline);
    }

    @Test
    void disablesChunkDetailsForPlainPromptSummary() {
        ReviewTask task = new ReviewTask();
        task.setMqRetries(0);
        task.setLlmPromptSummary("PR octocat/Hello-World#42; files=2");

        var result = assembler.assemble(
            task,
            baseItem(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );

        assertThat(result.chunkedReview().enabled()).isFalse();
        assertThat(result.chunkedReview().chunkCount()).isZero();
        assertThat(result.riskLevel()).isEqualTo("info");
    }

    @Test
    void prSummaryUsesAggregatedSeverityCountsBeyondInitialFindingPage() {
        ReviewTask task = new ReviewTask();
        task.setMqRetries(0);

        var result = assembler.assemble(
            task,
            baseItem(),
            List.of(new ReviewFindingDto("low", "src/App.java", 12, "minor", "fix later")),
            List.of(),
            List.of(new ChangedFileDto("src/App.java", "modified", 12, 3)),
            List.of(),
            30,
            0,
            8,
            new FindingSeverityCountsDto(0L, 2L, 5L, 23L, 0L)
        );

        assertThat(result.findingSeverityCounts().highOrZero()).isEqualTo(2);
        assertThat(result.prSummary().recommendMerge()).isFalse();
        assertThat(result.prSummary().summary()).contains("8 个变更文件、30 条审查发现");
    }

    private ReviewTaskListItem baseItem() {
        return new ReviewTaskListItem(
            521L,
            42,
            "Review task",
            "Hello-World",
            "octocat",
            "abc123",
            "main",
            "completed",
            "info",
            0,
            "completed",
            "manual_input",
            "manual_input",
            "2026-06-19 10:00:00",
            "0 分 10 秒",
            null,
            null,
            null
        );
    }
}
