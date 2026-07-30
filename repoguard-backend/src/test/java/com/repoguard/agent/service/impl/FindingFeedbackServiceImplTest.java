package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.ReviewFindingRiskRecalibrator;
import com.repoguard.agent.review.task.ReviewTaskTransitionStore;
import com.repoguard.agent.timeline.ReviewTimelineAppender;
import org.junit.jupiter.api.Test;

class FindingFeedbackServiceImplTest {

    @Test
    void constructorRejectsMissingCacheEvictionService() {
        assertThatThrownBy(() -> new FindingFeedbackServiceImpl(
            org.mockito.Mockito.mock(ReviewTaskMapper.class),
            org.mockito.Mockito.mock(ReviewFindingMapper.class),
            org.mockito.Mockito.mock(ReviewTimelineAppender.class),
            null,
            org.mockito.Mockito.mock(ReviewFindingRiskRecalibrator.class),
            org.mockito.Mockito.mock(ReviewTaskTransitionStore.class)
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("cacheEvictionService");
    }
}
