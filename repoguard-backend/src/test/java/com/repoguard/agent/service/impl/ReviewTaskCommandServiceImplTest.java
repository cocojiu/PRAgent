package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.review.task.HumanReviewCommandService;
import com.repoguard.agent.review.task.ManualReviewCreationService;
import com.repoguard.agent.review.task.ReviewTaskRetryService;
import org.junit.jupiter.api.Test;

class ReviewTaskCommandServiceImplTest {

    @Test
    void constructorRejectsMissingManualReviewCreationService() {
        assertThatThrownBy(() -> new ReviewTaskCommandServiceImpl(
            org.mockito.Mockito.mock(HumanReviewCommandService.class),
            org.mockito.Mockito.mock(ReviewTaskRetryService.class),
            null
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("manualReviewCreationService");
    }
}
