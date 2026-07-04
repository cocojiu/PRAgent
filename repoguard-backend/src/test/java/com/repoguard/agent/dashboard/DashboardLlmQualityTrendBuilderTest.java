package com.repoguard.agent.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.dto.DashboardLlmQualityTrendCount;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class DashboardLlmQualityTrendBuilderTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-06-19T10:00:00Z"), ZoneId.of("UTC"));

    private final DashboardLlmQualityTrendBuilder builder = DashboardLlmQualityTrendBuilder.forTest(
        new DashboardLlmQualityFormatter(),
        FIXED_CLOCK
    );

    @Test
    void defaultsUnsupportedWindowToSevenDays() {
        DashboardLlmQualityTrendBuilder.Window nullWindow = builder.window(null);
        DashboardLlmQualityTrendBuilder.Window unsupportedWindow = builder.window(14);

        assertThat(nullWindow.days()).isEqualTo(7);
        assertThat(nullWindow.startDate()).isEqualTo(LocalDate.of(2026, 6, 13));
        assertThat(unsupportedWindow.days()).isEqualTo(7);
        assertThat(unsupportedWindow.startDate()).isEqualTo(LocalDate.of(2026, 6, 13));
    }

    @Test
    void supportsThirtyAndNinetyDayWindows() {
        DashboardLlmQualityTrendBuilder.Window thirtyDayWindow = builder.window(30);
        DashboardLlmQualityTrendBuilder.Window ninetyDayWindow = builder.window(90);

        assertThat(thirtyDayWindow.days()).isEqualTo(30);
        assertThat(thirtyDayWindow.startDate()).isEqualTo(LocalDate.of(2026, 5, 21));
        assertThat(ninetyDayWindow.days()).isEqualTo(90);
        assertThat(ninetyDayWindow.startDate()).isEqualTo(LocalDate.of(2026, 3, 22));
    }

    @Test
    void buildsDenseTrendAndFillsMissingDaysWithZeroes() {
        DashboardLlmQualityTrendBuilder.Window window = new DashboardLlmQualityTrendBuilder.Window(3, LocalDate.of(2026, 6, 17));
        List<DashboardLlmQualityTrendCount> trendCounts = List.of(
            trendCount("2026-06-17", 4L, 3L, 1L, 0L),
            trendCount("2026-06-19", 2L, 1L, 0L, 1L),
            trendCount("", 99L, 99L, 99L, 99L)
        );

        var trend = builder.build(trendCounts, window);

        assertThat(trend).hasSize(3);
        assertThat(trend.get(0).date()).isEqualTo("06-17");
        assertThat(trend.get(0).taskCount()).isEqualTo(4L);
        assertThat(trend.get(0).parseSuccessRate()).isEqualTo("75.0%");
        assertThat(trend.get(0).fallbackRate()).isEqualTo("25.0%");
        assertThat(trend.get(1).date()).isEqualTo("06-18");
        assertThat(trend.get(1).taskCount()).isZero();
        assertThat(trend.get(1).parseSuccessRate()).isEqualTo("0.0%");
        assertThat(trend.get(2).date()).isEqualTo("06-19");
        assertThat(trend.get(2).taskCount()).isEqualTo(2L);
        assertThat(trend.get(2).partialFallbackRate()).isEqualTo("50.0%");
    }

    private DashboardLlmQualityTrendCount trendCount(
        String dayKey,
        Long taskCount,
        Long parseSuccessCount,
        Long fallbackCount,
        Long partialFallbackCount
    ) {
        DashboardLlmQualityTrendCount count = new DashboardLlmQualityTrendCount();
        count.setDayKey(dayKey);
        count.setTaskCount(taskCount);
        count.setParseSuccessCount(parseSuccessCount);
        count.setFallbackCount(fallbackCount);
        count.setPartialFallbackCount(partialFallbackCount);
        return count;
    }
}
