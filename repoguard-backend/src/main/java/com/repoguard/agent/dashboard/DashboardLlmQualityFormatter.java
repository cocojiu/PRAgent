package com.repoguard.agent.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class DashboardLlmQualityFormatter {

    public String averageDuration(BigDecimal durationMs) {
        int roundedDurationMs = roundedInt(durationMs);
        if (roundedDurationMs <= 0) {
            return "0 ms";
        }
        if (roundedDurationMs < 1000) {
            return roundedDurationMs + " ms";
        }
        return String.format(Locale.ROOT, "%.1f s", roundedDurationMs / 1000.0);
    }

    public String averageTokens(BigDecimal averageTokens) {
        if (averageTokens == null || averageTokens.compareTo(BigDecimal.ZERO) <= 0) {
            return "0";
        }
        return String.format(Locale.ROOT, "%.0f", averageTokens);
    }

    public String averageCost(BigDecimal averageCost) {
        if (averageCost == null || averageCost.compareTo(BigDecimal.ZERO) <= 0) {
            return "$0.000000";
        }
        return "$" + averageCost.setScale(6, RoundingMode.HALF_UP).toPlainString();
    }

    public String rate(long value, long total) {
        if (total <= 0) {
            return "0.0%";
        }
        return String.format(Locale.ROOT, "%.1f%%", value * 100.0 / total);
    }

    private int roundedInt(BigDecimal value) {
        return value == null ? 0 : value.setScale(0, RoundingMode.HALF_UP).intValue();
    }
}
