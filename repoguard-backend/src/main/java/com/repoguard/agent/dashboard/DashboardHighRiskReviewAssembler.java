package com.repoguard.agent.dashboard;

import com.repoguard.agent.dto.DashboardHighRiskReview;
import com.repoguard.agent.dto.HighRiskReviewDto;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class DashboardHighRiskReviewAssembler {

    private static final DateTimeFormatter REVIEWED_AT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final DashboardStatusMapper statusMapper;

    public DashboardHighRiskReviewAssembler(DashboardStatusMapper statusMapper) {
        this.statusMapper = Objects.requireNonNull(statusMapper, "statusMapper must not be null");
    }

    public List<HighRiskReviewDto> assemble(List<DashboardHighRiskReview> highRiskReviews) {
        return nullToEmpty(highRiskReviews).stream()
            .map(review -> new HighRiskReviewDto(
                review.getTitle(),
                review.getRepository(),
                lower(review.getRiskLevel()),
                safeRuleHits(review),
                formatReviewedAt(review.getCreatedAt()),
                statusMapper.reviewTaskStatusText(review.getStatus())
            ))
            .toList();
    }

    private long safeRuleHits(DashboardHighRiskReview review) {
        return review.getRuleHits() == null ? 0L : review.getRuleHits();
    }

    private String lower(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private String formatReviewedAt(LocalDateTime reviewedAt) {
        return reviewedAt == null ? "" : reviewedAt.format(REVIEWED_AT_FORMATTER);
    }

    private <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }
}
