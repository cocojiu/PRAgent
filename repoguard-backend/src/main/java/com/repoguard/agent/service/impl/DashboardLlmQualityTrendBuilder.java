package com.repoguard.agent.service.impl;

import com.repoguard.agent.dto.DashboardLlmQualityTrendCount;
import com.repoguard.agent.dto.LlmQualityTrendPointDto;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class DashboardLlmQualityTrendBuilder {

    private static final DateTimeFormatter TREND_DATE_FORMATTER = DateTimeFormatter.ofPattern("MM-dd");

    private final DashboardLlmQualityFormatter llmQualityFormatter;
    private final Clock clock;

    @Autowired
    DashboardLlmQualityTrendBuilder(DashboardLlmQualityFormatter llmQualityFormatter) {
        this(llmQualityFormatter, Clock.systemDefaultZone());
    }

    private DashboardLlmQualityTrendBuilder(DashboardLlmQualityFormatter llmQualityFormatter, Clock clock) {
        this.llmQualityFormatter = llmQualityFormatter;
        this.clock = clock;
    }

    static DashboardLlmQualityTrendBuilder forTest(DashboardLlmQualityFormatter llmQualityFormatter, Clock clock) {
        return new DashboardLlmQualityTrendBuilder(llmQualityFormatter, clock);
    }

    Window window(Integer days) {
        int normalizedDays = normalizeDays(days);
        return new Window(normalizedDays, today().minusDays(normalizedDays - 1L));
    }

    Window window(Integer days, LocalDate latestReviewDate) {
        int normalizedDays = normalizeDays(days);
        LocalDate currentStartDate = today().minusDays(normalizedDays - 1L);
        if (latestReviewDate == null || !latestReviewDate.isBefore(currentStartDate)) {
            return new Window(normalizedDays, currentStartDate);
        }
        return new Window(normalizedDays, latestReviewDate.minusDays(normalizedDays - 1L));
    }

    List<LlmQualityTrendPointDto> build(List<DashboardLlmQualityTrendCount> trendCounts, Window window) {
        Map<String, DashboardLlmQualityTrendCount> countsByDay = nullToEmpty(trendCounts).stream()
            .filter(count -> StringUtils.hasText(count.getDayKey()))
            .collect(Collectors.toMap(DashboardLlmQualityTrendCount::getDayKey, java.util.function.Function.identity(), (first, second) -> first));
        return IntStream.rangeClosed(0, window.days() - 1)
            .mapToObj(window.startDate()::plusDays)
            .map(date -> trendPoint(date, countsByDay.get(date.toString())))
            .toList();
    }

    private LlmQualityTrendPointDto trendPoint(LocalDate date, DashboardLlmQualityTrendCount count) {
        long taskCount = safeTaskCount(count);
        return new LlmQualityTrendPointDto(
            date.format(TREND_DATE_FORMATTER),
            taskCount,
            llmQualityFormatter.rate(safeParseSuccessCount(count), taskCount),
            llmQualityFormatter.rate(safeFallbackCount(count), taskCount),
            llmQualityFormatter.rate(safePartialFallbackCount(count), taskCount)
        );
    }

    private int normalizeDays(Integer days) {
        if (days == null) {
            return 7;
        }
        return switch (days) {
            case 30 -> 30;
            case 90 -> 90;
            default -> 7;
        };
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private long safeTaskCount(DashboardLlmQualityTrendCount count) {
        return count == null || count.getTaskCount() == null ? 0L : count.getTaskCount();
    }

    private long safeParseSuccessCount(DashboardLlmQualityTrendCount count) {
        return count == null || count.getParseSuccessCount() == null ? 0L : count.getParseSuccessCount();
    }

    private long safeFallbackCount(DashboardLlmQualityTrendCount count) {
        return count == null || count.getFallbackCount() == null ? 0L : count.getFallbackCount();
    }

    private long safePartialFallbackCount(DashboardLlmQualityTrendCount count) {
        return count == null || count.getPartialFallbackCount() == null ? 0L : count.getPartialFallbackCount();
    }

    private <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    record Window(int days, LocalDate startDate) {
    }
}
