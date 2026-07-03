package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DashboardLlmTrendDaysTest {

    @Test
    void normalizesUnsupportedValuesToDefaultWindow() {
        assertThat(DashboardLlmTrendDays.normalize(null)).isEqualTo(7);
        assertThat(DashboardLlmTrendDays.normalize(7)).isEqualTo(7);
        assertThat(DashboardLlmTrendDays.normalize(0)).isEqualTo(7);
        assertThat(DashboardLlmTrendDays.normalize(14)).isEqualTo(7);
        assertThat(DashboardLlmTrendDays.normalize(-1)).isEqualTo(7);
    }

    @Test
    void keepsSupportedExtendedWindows() {
        assertThat(DashboardLlmTrendDays.normalize(30)).isEqualTo(30);
        assertThat(DashboardLlmTrendDays.normalize(90)).isEqualTo(90);
    }
}
