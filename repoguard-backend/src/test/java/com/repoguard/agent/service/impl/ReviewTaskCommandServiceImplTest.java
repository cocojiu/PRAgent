package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.messaging.ReviewTaskPublisher;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;

class ReviewTaskCommandServiceImplTest {

    @Test
    void constructorRejectsMissingStateMachine() {
        assertThatThrownBy(() -> new ReviewTaskCommandServiceImpl(
            org.mockito.Mockito.mock(ReviewTaskMapper.class),
            org.mockito.Mockito.mock(ReviewTimelineMapper.class),
            org.mockito.Mockito.mock(ReviewTaskPublisher.class),
            null,
            null,
            null,
            null,
            Runnable::run,
            new ManualReviewIdempotencyCoordinator(org.mockito.Mockito.mock(ScheduledExecutorService.class)),
            null,
            null,
            null,
            null
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("reviewTaskStateMachine");
    }
}
