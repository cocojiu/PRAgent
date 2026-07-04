package com.repoguard.agent.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class DashboardLlmQualityFormatterTest {

    private final DashboardLlmQualityFormatter formatter = new DashboardLlmQualityFormatter();

    @Test
    void formatsAverageDuration() {
        assertThat(formatter.averageDuration(null)).isEqualTo("0 ms");
        assertThat(formatter.averageDuration(BigDecimal.ZERO)).isEqualTo("0 ms");
        assertThat(formatter.averageDuration(BigDecimal.valueOf(999))).isEqualTo("999 ms");
        assertThat(formatter.averageDuration(BigDecimal.valueOf(1499))).isEqualTo("1.5 s");
    }

    @Test
    void formatsAverageTokens() {
        assertThat(formatter.averageTokens(null)).isEqualTo("0");
        assertThat(formatter.averageTokens(BigDecimal.ZERO)).isEqualTo("0");
        assertThat(formatter.averageTokens(BigDecimal.valueOf(1200.4))).isEqualTo("1200");
        assertThat(formatter.averageTokens(BigDecimal.valueOf(1200.5))).isEqualTo("1201");
    }

    @Test
    void formatsAverageCost() {
        assertThat(formatter.averageCost(null)).isEqualTo("$0.000000");
        assertThat(formatter.averageCost(BigDecimal.ZERO)).isEqualTo("$0.000000");
        assertThat(formatter.averageCost(new BigDecimal("0.0001234"))).isEqualTo("$0.000123");
        assertThat(formatter.averageCost(new BigDecimal("0.0001235"))).isEqualTo("$0.000124");
    }

    @Test
    void formatsRates() {
        assertThat(formatter.rate(0, 0)).isEqualTo("0.0%");
        assertThat(formatter.rate(1, 3)).isEqualTo("33.3%");
        assertThat(formatter.rate(2, 3)).isEqualTo("66.7%");
    }
}
