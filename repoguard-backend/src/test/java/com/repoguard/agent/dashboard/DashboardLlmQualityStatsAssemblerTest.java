package com.repoguard.agent.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.dto.DashboardLlmQualityModelStat;
import com.repoguard.agent.dto.DashboardLlmQualityRepositoryStat;
import com.repoguard.agent.dto.LlmQualityByModelDto;
import com.repoguard.agent.dto.LlmQualityByRepositoryDto;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class DashboardLlmQualityStatsAssemblerTest {

    private final DashboardLlmQualityStatsAssembler assembler = new DashboardLlmQualityStatsAssembler(
        new DashboardLlmQualityFormatter()
    );

    @Test
    void assemblesModelStatsForDisplay() {
        DashboardLlmQualityModelStat stat = modelStat();
        stat.setModelLabel("openai / gpt-4.1");
        stat.setTaskCount(20L);
        stat.setAverageDurationMs(new BigDecimal("1250"));
        stat.setAverageTokens(new BigDecimal("1234.4"));
        stat.setAverageCost(new BigDecimal("0.1234564"));
        stat.setParseSuccessCount(19L);
        stat.setFallbackCount(2L);
        stat.setPartialFallbackCount(1L);
        stat.setReviewedFeedbackCount(10L);
        stat.setValidFeedbackCount(8L);
        stat.setFalsePositiveFeedbackCount(1L);

        List<LlmQualityByModelDto> result = assembler.assembleByModel(List.of(stat));

        assertThat(result).containsExactly(new LlmQualityByModelDto(
            "openai / gpt-4.1",
            20L,
            "1.3 s",
            "1234",
            "$0.123456",
            "95.0%",
            "10.0%",
            "5.0%",
            "80.0%",
            "10.0%"
        ));
    }

    @Test
    void assemblesRepositoryStatsForDisplay() {
        DashboardLlmQualityRepositoryStat stat = repositoryStat();
        stat.setRepositoryLabel("demo/repo");
        stat.setTaskCount(25L);
        stat.setFallbackCount(5L);
        stat.setPartialFallbackCount(2L);
        stat.setReviewedFeedbackCount(10L);
        stat.setValidFeedbackCount(7L);
        stat.setFalsePositiveFeedbackCount(1L);

        List<LlmQualityByRepositoryDto> result = assembler.assembleByRepository(List.of(stat));

        assertThat(result).containsExactly(new LlmQualityByRepositoryDto(
            "demo/repo",
            25L,
            "20.0%",
            "8.0%",
            "70.0%",
            "10.0%"
        ));
    }

    @Test
    void handlesNullStatsAndCounts() {
        DashboardLlmQualityModelStat modelStat = modelStat();
        DashboardLlmQualityRepositoryStat repositoryStat = repositoryStat();

        assertThat(assembler.assembleByModel(null)).isEmpty();
        assertThat(assembler.assembleByRepository(null)).isEmpty();
        assertThat(assembler.assembleByModel(List.of(modelStat)).get(0))
            .isEqualTo(new LlmQualityByModelDto(null, 0L, "0 ms", "0", "$0.000000", "0.0%", "0.0%", "0.0%", "0.0%", "0.0%"));
        assertThat(assembler.assembleByRepository(List.of(repositoryStat)).get(0))
            .isEqualTo(new LlmQualityByRepositoryDto(null, 0L, "0.0%", "0.0%", "0.0%", "0.0%"));
    }

    private DashboardLlmQualityModelStat modelStat() {
        return new DashboardLlmQualityModelStat();
    }

    private DashboardLlmQualityRepositoryStat repositoryStat() {
        return new DashboardLlmQualityRepositoryStat();
    }
}
