package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.timeline.ReviewTimelineAppender;
import org.junit.jupiter.api.Test;

class HumanReviewCommandServiceTest {

    @Test
    void constructorRejectsMissingStateMachine() {
        assertThatThrownBy(() -> new HumanReviewCommandService(
            org.mockito.Mockito.mock(ReviewTaskMapper.class),
            org.mockito.Mockito.mock(ReviewTimelineAppender.class),
            null,
            null
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("reviewTaskStateMachine");
    }
}
