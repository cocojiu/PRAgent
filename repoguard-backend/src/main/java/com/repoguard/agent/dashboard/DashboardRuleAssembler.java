package com.repoguard.agent.dashboard;

import com.repoguard.agent.dto.ChartSliceDto;
import com.repoguard.agent.dto.DashboardRuleHitCount;
import com.repoguard.agent.dto.DashboardRulesResponse;
import com.repoguard.agent.dto.FailedRuleStatDto;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class DashboardRuleAssembler {

    private final DashboardRuleDisplayMapper ruleDisplayMapper;

    public DashboardRuleAssembler(DashboardRuleDisplayMapper ruleDisplayMapper) {
        this.ruleDisplayMapper = ruleDisplayMapper;
    }

    public DashboardRulesResponse assemble(List<DashboardRuleHitCount> ruleHitCounts) {
        List<DashboardRuleHitCount> ordered = orderedRuleHitCounts(ruleHitCounts);
        long total = ordered.stream().mapToLong(this::safeTotal).sum();
        return new DashboardRulesResponse(
            ruleHits(ordered, total),
            failedRules(ordered, total)
        );
    }

    private List<DashboardRuleHitCount> orderedRuleHitCounts(List<DashboardRuleHitCount> ruleHitCounts) {
        return nullToEmpty(ruleHitCounts).stream()
            .sorted(Comparator.comparingLong(this::safeTotal).reversed())
            .toList();
    }

    private List<ChartSliceDto> ruleHits(List<DashboardRuleHitCount> ruleHitCounts, long total) {
        return ruleHitCounts.stream()
            .map(count -> new ChartSliceDto(
                ruleDisplayMapper.ruleName(count.getRuleId()),
                safeTotal(count),
                ruleDisplayMapper.ruleColor(count.getRuleId()),
                percent(safeTotal(count), total)
            ))
            .toList();
    }

    private List<FailedRuleStatDto> failedRules(List<DashboardRuleHitCount> ruleHitCounts, long total) {
        return ruleHitCounts.stream()
            .map(count -> new FailedRuleStatDto(
                ruleDisplayMapper.ruleName(count.getRuleId()),
                safeTotal(count),
                "0.0%",
                "down",
                percent(safeTotal(count), total)
            ))
            .toList();
    }

    private String percent(long value, long total) {
        if (total == 0) {
            return "0.0%";
        }
        return String.format(Locale.ROOT, "%.1f%%", value * 100.0 / total);
    }

    private long safeTotal(DashboardRuleHitCount count) {
        return count.getTotal() == null ? 0L : count.getTotal();
    }

    private <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }
}
