package com.repoguard.agent.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.dto.DashboardReviewTrendCount;
import com.repoguard.agent.dto.ReviewTrendPointDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class DashboardReviewTrendAssemblerTest {

    private final DashboardReviewTrendAssembler assembler = new DashboardReviewTrendAssembler();

    @Test
    void assemblesReviewTrendPointsInSourceOrder() {
        List<ReviewTrendPointDto> result = assembler.assemble(List.of(
            trendCount("06-15", 2L),
            trendCount("06-16", 3L)
        ));

        assertThat(result).containsExactly(
            new ReviewTrendPointDto("06-15", 2L),
            new ReviewTrendPointDto("06-16", 3L)
        );
    }

    @Test
    void handlesNullSourceAndTotals() {
        assertThat(assembler.assemble(null)).isEmpty();
        assertThat(assembler.assemble(List.of(trendCount("06-17", null))))
            .containsExactly(new ReviewTrendPointDto("06-17", 0L));
    }

    private DashboardReviewTrendCount trendCount(String dayLabel, Long total) {
        DashboardReviewTrendCount count = new DashboardReviewTrendCount();
        count.setDayLabel(dayLabel);
        count.setTotal(total);
        return count;
    }
}
