package com.repoguard.agent.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.dto.ChartSliceDto;
import com.repoguard.agent.dto.DashboardRiskLevelCount;
import java.util.List;
import org.junit.jupiter.api.Test;

class DashboardRiskDistributionAssemblerTest {

    private final DashboardRiskDistributionAssembler assembler = new DashboardRiskDistributionAssembler(
        new DashboardOverviewDisplayMapper()
    );

    @Test
    void assemblesRiskDistributionInStableDisplayOrder() {
        List<ChartSliceDto> result = assembler.assemble(List.of(
            riskLevelCount("HIGH", 1L),
            riskLevelCount("MEDIUM", 2L),
            riskLevelCount("INFO", 1L)
        ));

        assertThat(result).extracting(ChartSliceDto::name)
            .containsExactly("\u9ad8\u98ce\u9669", "\u4e2d\u98ce\u9669", "\u4f4e\u98ce\u9669", "\u63d0\u793a");
        assertThat(result).extracting(ChartSliceDto::color)
            .containsExactly("#ef4444", "#f59e0b", "#2563eb", "#22c55e");
        assertThat(result).extracting(ChartSliceDto::value)
            .containsExactly(1L, 2L, 0L, 1L);
        assertThat(result).extracting(ChartSliceDto::percent)
            .containsExactly("25.0%", "50.0%", "0.0%", "25.0%");
    }

    @Test
    void mergesDuplicateRiskLevelsAndTreatsNullTotalsAsZero() {
        List<ChartSliceDto> result = assembler.assemble(List.of(
            riskLevelCount("HIGH", 1L),
            riskLevelCount("HIGH", 2L),
            riskLevelCount("MEDIUM", null)
        ));

        assertThat(result).extracting(ChartSliceDto::value)
            .containsExactly(3L, 0L, 0L, 0L);
        assertThat(result).extracting(ChartSliceDto::percent)
            .containsExactly("100.0%", "0.0%", "0.0%", "0.0%");
    }

    @Test
    void nullSourceProducesZeroDistribution() {
        List<ChartSliceDto> result = assembler.assemble(null);

        assertThat(result).extracting(ChartSliceDto::value)
            .containsExactly(0L, 0L, 0L, 0L);
        assertThat(result).extracting(ChartSliceDto::percent)
            .containsExactly("0.0%", "0.0%", "0.0%", "0.0%");
    }

    private DashboardRiskLevelCount riskLevelCount(String riskLevel, Long total) {
        DashboardRiskLevelCount count = new DashboardRiskLevelCount();
        count.setRiskLevel(riskLevel);
        count.setTotal(total);
        return count;
    }
}
