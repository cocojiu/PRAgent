package com.repoguard.agent.dashboard;

import com.repoguard.agent.dto.DashboardReviewTrendCount;
import com.repoguard.agent.dto.ReviewTrendPointDto;
import java.util.List;

public class DashboardReviewTrendAssembler {

    public List<ReviewTrendPointDto> assemble(List<DashboardReviewTrendCount> reviewTrendCounts) {
        return nullToEmpty(reviewTrendCounts).stream()
            .map(count -> new ReviewTrendPointDto(count.getDayLabel(), safeTotal(count)))
            .toList();
    }

    private long safeTotal(DashboardReviewTrendCount count) {
        return count.getTotal() == null ? 0L : count.getTotal();
    }

    private <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }
}
