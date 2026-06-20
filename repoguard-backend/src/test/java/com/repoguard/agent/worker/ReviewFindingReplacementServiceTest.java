package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.review.ReviewFindingResult;
import com.repoguard.agent.review.ReviewResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReviewFindingReplacementServiceTest {

    private final ReviewFindingMapper reviewFindingMapper = org.mockito.Mockito.mock(ReviewFindingMapper.class);
    private final ReviewFindingReplacementService service = new ReviewFindingReplacementService(
        reviewFindingMapper,
        new ReviewFindingDeduplicator()
    );

    @Test
    void deletesExistingFindingsAndStoresDeduplicatedFindings() {
        ReviewResult reviewResult = ReviewResult.completed(
            "HIGH",
            List.of(
                new ReviewFindingResult("LOW", "LLM", "LLM", "src/App.java", 10, "Use logger", "Replace stdout"),
                new ReviewFindingResult("HIGH", "RULE", "RG-JAVA-002", "src/App.java", 10, "Use logger", "Use structured logger"),
                new ReviewFindingResult("MEDIUM", "RULE", "RG-JAVA-003", "src/Task.java", 20, "Avoid sleep", "Use awaitility")
            )
        );

        int count = service.replace(42L, reviewResult);

        assertThat(count).isEqualTo(2);
        verify(reviewFindingMapper).delete(any());
        ArgumentCaptor<ReviewFinding> findingCaptor = ArgumentCaptor.forClass(ReviewFinding.class);
        verify(reviewFindingMapper, org.mockito.Mockito.times(2)).insert(findingCaptor.capture());
        assertThat(findingCaptor.getAllValues()).extracting(ReviewFinding::getTaskId).containsOnly(42L);
        assertThat(findingCaptor.getAllValues()).extracting(ReviewFinding::getCategory).containsOnly("FINDING");
        assertThat(findingCaptor.getAllValues()).extracting(ReviewFinding::getFilePath).containsExactly(
            "src/App.java",
            "src/Task.java"
        );
        ReviewFinding merged = findingCaptor.getAllValues().get(0);
        assertThat(merged.getSeverity()).isEqualTo("HIGH");
        assertThat(merged.getSource()).isEqualTo("LLM+RULE");
        assertThat(merged.getRuleId()).isEqualTo("LLM / RG-JAVA-002");
        assertThat(merged.getRecommendation()).isEqualTo("Replace stdout / Use structured logger");
        assertThat(merged.getConfidence()).isEqualTo("HIGH");
        assertThat(merged.getIsBlocking()).isTrue();
        assertThat(merged.getFixExample()).isEqualTo("Replace stdout / Use structured logger");
        assertThat(merged.getReviewDimension()).contains("LLM").contains("PROJECT_RULE");
    }

    @Test
    void deletesExistingFindingsWhenReviewHasNoFindings() {
        int count = service.replace(42L, ReviewResult.completed("INFO", List.of()));

        assertThat(count).isZero();
        verify(reviewFindingMapper).delete(any());
        verify(reviewFindingMapper, org.mockito.Mockito.never()).insert(any(ReviewFinding.class));
    }
}
