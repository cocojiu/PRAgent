package com.repoguard.agent.dashboard;

import com.repoguard.agent.dto.DashboardLlmQualityModelStat;
import com.repoguard.agent.dto.DashboardLlmQualityRepositoryStat;
import com.repoguard.agent.dto.LlmQualityByModelDto;
import com.repoguard.agent.dto.LlmQualityByRepositoryDto;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class DashboardLlmQualityStatsAssembler {

    private final DashboardLlmQualityFormatter formatter;

    public DashboardLlmQualityStatsAssembler(DashboardLlmQualityFormatter formatter) {
        this.formatter = Objects.requireNonNull(formatter, "formatter must not be null");
    }

    public List<LlmQualityByModelDto> assembleByModel(List<DashboardLlmQualityModelStat> stats) {
        return nullToEmpty(stats).stream()
            .map(stat -> {
                long taskCount = safe(stat.getTaskCount());
                long reviewedFeedbackCount = safe(stat.getReviewedFeedbackCount());
                return new LlmQualityByModelDto(
                    stat.getModelLabel(),
                    taskCount,
                    formatter.averageDuration(stat.getAverageDurationMs()),
                    formatter.averageTokens(stat.getAverageTokens()),
                    formatter.averageCost(stat.getAverageCost()),
                    formatter.rate(safe(stat.getParseSuccessCount()), taskCount),
                    formatter.rate(safe(stat.getFallbackCount()), taskCount),
                    formatter.rate(safe(stat.getPartialFallbackCount()), taskCount),
                    formatter.rate(safe(stat.getValidFeedbackCount()), reviewedFeedbackCount),
                    formatter.rate(safe(stat.getFalsePositiveFeedbackCount()), reviewedFeedbackCount)
                );
            })
            .toList();
    }

    public List<LlmQualityByRepositoryDto> assembleByRepository(List<DashboardLlmQualityRepositoryStat> stats) {
        return nullToEmpty(stats).stream()
            .map(stat -> {
                long taskCount = safe(stat.getTaskCount());
                long reviewedFeedbackCount = safe(stat.getReviewedFeedbackCount());
                return new LlmQualityByRepositoryDto(
                    stat.getRepositoryLabel(),
                    taskCount,
                    formatter.rate(safe(stat.getFallbackCount()), taskCount),
                    formatter.rate(safe(stat.getPartialFallbackCount()), taskCount),
                    formatter.rate(safe(stat.getValidFeedbackCount()), reviewedFeedbackCount),
                    formatter.rate(safe(stat.getFalsePositiveFeedbackCount()), reviewedFeedbackCount)
                );
            })
            .toList();
    }

    private long safe(Long value) {
        return value == null ? 0L : value;
    }

    private <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }
}
