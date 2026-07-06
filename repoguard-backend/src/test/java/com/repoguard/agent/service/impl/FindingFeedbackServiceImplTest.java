package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.timeline.ReviewTimelineAppender;
import org.junit.jupiter.api.Test;

class FindingFeedbackServiceImplTest {

    @Test
    void constructorRejectsMissingCacheEvictionService() {
        assertThatThrownBy(() -> new FindingFeedbackServiceImpl(
            org.mockito.Mockito.mock(ReviewTaskMapper.class),
            org.mockito.Mockito.mock(ReviewFindingMapper.class),
            org.mockito.Mockito.mock(ReviewTimelineAppender.class),
            null
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("cacheEvictionService");
    }
}
