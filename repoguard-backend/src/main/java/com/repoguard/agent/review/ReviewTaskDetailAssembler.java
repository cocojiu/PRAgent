package com.repoguard.agent.review;

import com.repoguard.agent.dto.ChangedFileDto;
import com.repoguard.agent.dto.ChunkedReviewDto;
import com.repoguard.agent.dto.FindingSeverityCountsDto;
import com.repoguard.agent.dto.LlmStatusDto;
import com.repoguard.agent.dto.MissingTestDto;
import com.repoguard.agent.dto.PrRiskProfileDto;
import com.repoguard.agent.dto.RabbitMqStatusDto;
import com.repoguard.agent.dto.ReviewFindingDto;
import com.repoguard.agent.dto.ReviewTaskDetail;
import com.repoguard.agent.dto.ReviewTaskListItem;
import com.repoguard.agent.dto.ReviewTimelineItem;
import com.repoguard.agent.entity.ReviewTask;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ReviewTaskDetailAssembler {

    private final ReviewRiskProfileBuilder riskProfileBuilder;
    private final PrReviewSummaryBuilder reviewSummaryBuilder;

    public ReviewTaskDetailAssembler(
        ReviewRiskProfileBuilder riskProfileBuilder,
        PrReviewSummaryBuilder reviewSummaryBuilder
    ) {
        this.riskProfileBuilder = Objects.requireNonNull(riskProfileBuilder, "riskProfileBuilder must not be null");
        this.reviewSummaryBuilder = Objects.requireNonNull(reviewSummaryBuilder, "reviewSummaryBuilder must not be null");
    }

    public ReviewTaskDetail assemble(
        ReviewTask task,
        ReviewTaskListItem item,
        List<ReviewFindingDto> findings,
        List<MissingTestDto> missingTests,
        List<ChangedFileDto> changedFiles,
        List<ReviewTimelineItem> timeline
    ) {
        return assemble(
            task,
            item,
            findings,
            missingTests,
            changedFiles,
            timeline,
            sizeOf(findings),
            sizeOf(missingTests),
            sizeOf(changedFiles),
            FindingSeverityCountsDto.fromFindings(findings)
        );
    }

    public ReviewTaskDetail assemble(
        ReviewTask task,
        ReviewTaskListItem item,
        List<ReviewFindingDto> findings,
        List<MissingTestDto> missingTests,
        List<ChangedFileDto> changedFiles,
        List<ReviewTimelineItem> timeline,
        long findingTotal,
        long missingTestTotal,
        long changedFileTotal
    ) {
        return assemble(
            task,
            item,
            findings,
            missingTests,
            changedFiles,
            timeline,
            findingTotal,
            missingTestTotal,
            changedFileTotal,
            FindingSeverityCountsDto.fromFindings(findings)
        );
    }

    public ReviewTaskDetail assemble(
        ReviewTask task,
        ReviewTaskListItem item,
        List<ReviewFindingDto> findings,
        List<MissingTestDto> missingTests,
        List<ChangedFileDto> changedFiles,
        List<ReviewTimelineItem> timeline,
        long findingTotal,
        long missingTestTotal,
        long changedFileTotal,
        FindingSeverityCountsDto findingSeverityCounts
    ) {
        FindingSeverityCountsDto effectiveSeverityCounts = findingSeverityCounts == null
            ? FindingSeverityCountsDto.fromFindings(findings)
            : findingSeverityCounts;
        PrRiskProfileDto riskProfile = summaryOnly(findings, changedFiles)
            ? riskProfileBuilder.buildSummary(item, effectiveSeverityCounts, findingTotal, changedFileTotal)
            : riskProfileBuilder.build(item, findings, changedFiles);
        String effectiveRiskLevel = effectiveRiskLevel(item.riskLevel(), riskProfile);
        return new ReviewTaskDetail(
            item.id(),
            item.prNumber(),
            item.title(),
            item.repository(),
            item.organization(),
            item.commit(),
            item.branch(),
            item.status(),
            effectiveRiskLevel,
            item.mqRetries(),
            item.llmStatus(),
            item.source(),
            item.triggerSource(),
            item.createdAt(),
            item.duration(),
            item.failureCategory(),
            item.failureReason(),
            item.failureSuggestion(),
            task.getPrUrl(),
            findings,
            missingTests,
            changedFiles,
            timeline,
            riskProfile,
            reviewSummaryBuilder.build(
                item,
                findings,
                missingTests,
                changedFiles,
                riskProfile,
                effectiveSeverityCounts,
                findingTotal,
                missingTestTotal,
                changedFileTotal
            ),
            new LlmStatusDto(
                item.llmStatus(),
                item.duration(),
                effectiveRiskLevel,
                lower(task.getLlmProvider()),
                task.getLlmModel(),
                task.getLlmDurationMs(),
                lower(task.getLlmParseStatus()),
                task.getLlmFallbackReason(),
                task.getLlmPromptSummary(),
                task.getLlmPromptTokens(),
                task.getLlmCompletionTokens(),
                task.getLlmTotalTokens(),
                task.getLlmEstimatedCost() == null ? null : task.getLlmEstimatedCost().toPlainString()
            ),
            buildChunkedReview(task.getLlmPromptSummary()),
            new RabbitMqStatusDto(task.getMqRetries() + 1, task.getMqRetries(), "confirmed"),
            item.humanReviewRequired(),
            item.humanReviewStatus(),
            item.humanReviewNote(),
            item.humanReviewBy(),
            item.humanReviewedAt(),
            findingTotal,
            missingTestTotal,
            changedFileTotal,
            effectiveSeverityCounts
        );
    }

    private String effectiveRiskLevel(String taskRiskLevel, PrRiskProfileDto riskProfile) {
        if (riskProfile != null && StringUtils.hasText(riskProfile.level())) {
            return riskProfile.level();
        }
        return lower(taskRiskLevel);
    }

    private boolean summaryOnly(List<ReviewFindingDto> findings, List<ChangedFileDto> changedFiles) {
        return (findings == null || findings.isEmpty()) && (changedFiles == null || changedFiles.isEmpty());
    }

    private ChunkedReviewDto buildChunkedReview(String promptSummary) {
        if (!StringUtils.hasText(promptSummary)) {
            return ChunkedReviewDto.disabled();
        }
        Map<String, String> summary = parsePromptSummary(promptSummary);
        if (!"true".equalsIgnoreCase(summary.get("chunked"))) {
            return ChunkedReviewDto.disabled();
        }
        return new ChunkedReviewDto(
            true,
            parsePositiveInt(summary.get("chunks")),
            lower(summary.get("aggregateRisk")),
            parsePositiveInt(summary.get("aggregateFindings")),
            parsePositiveInt(summary.get("failedChunks")),
            parseChunkReasons(summary.get("chunkReasons"))
        );
    }

    private Map<String, String> parsePromptSummary(String promptSummary) {
        return List.of(promptSummary.split(";")).stream()
            .map(String::trim)
            .filter(part -> part.contains("="))
            .map(part -> part.split("=", 2))
            .filter(parts -> parts.length == 2 && StringUtils.hasText(parts[0]))
            .collect(Collectors.toMap(
                parts -> parts[0].trim(),
                parts -> parts[1].trim(),
                (first, second) -> second
            ));
    }

    private Integer parsePositiveInt(String value) {
        if (!StringUtils.hasText(value)) {
            return 0;
        }
        try {
            return Math.max(Integer.parseInt(value.trim()), 0);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private List<String> parseChunkReasons(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        return List.of(value.split(",")).stream()
            .map(String::trim)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private long sizeOf(List<?> items) {
        return items == null ? 0L : items.size();
    }
}
