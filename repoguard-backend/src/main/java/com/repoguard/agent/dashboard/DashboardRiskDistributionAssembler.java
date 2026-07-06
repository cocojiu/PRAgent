package com.repoguard.agent.dashboard;

import com.repoguard.agent.dto.ChartSliceDto;
import com.repoguard.agent.dto.DashboardRiskLevelCount;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public class DashboardRiskDistributionAssembler {

    private final DashboardOverviewDisplayMapper overviewDisplayMapper;

    public DashboardRiskDistributionAssembler(DashboardOverviewDisplayMapper overviewDisplayMapper) {
        this.overviewDisplayMapper = overviewDisplayMapper;
    }

    public List<ChartSliceDto> assemble(List<DashboardRiskLevelCount> riskLevelCounts) {
        Map<String, Long> countByRisk = nullToEmpty(riskLevelCounts).stream()
            .collect(Collectors.toMap(DashboardRiskLevelCount::getRiskLevel, this::safeTotal, Long::sum));
        long total = countByRisk.values().stream().mapToLong(Long::longValue).sum();

        return List.of(
            riskSlice("HIGH", countByRisk.getOrDefault("HIGH", 0L), total),
            riskSlice("MEDIUM", countByRisk.getOrDefault("MEDIUM", 0L), total),
            riskSlice("LOW", countByRisk.getOrDefault("LOW", 0L), total),
            riskSlice("INFO", countByRisk.getOrDefault("INFO", 0L), total)
        );
    }

    private ChartSliceDto riskSlice(String riskLevel, long value, long total) {
        DashboardOverviewDisplayMapper.RiskLevelDisplay display = overviewDisplayMapper.riskLevel(riskLevel);
        return new ChartSliceDto(display.name(), value, display.color(), percent(value, total));
    }

    private String percent(long value, long total) {
        if (total == 0) {
            return "0.0%";
        }
        return String.format(Locale.ROOT, "%.1f%%", value * 100.0 / total);
    }

    private long safeTotal(DashboardRiskLevelCount count) {
        return count.getTotal() == null ? 0L : count.getTotal();
    }

    private <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }
}
