package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.timeline.ReviewTimelineAppender;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class ManualReviewCreationServiceTest {

    @Test
    void constructorRejectsMissingStateMachine() {
        assertThatThrownBy(() -> new ManualReviewCreationService(
            org.mockito.Mockito.mock(ReviewTaskMapper.class),
            org.mockito.Mockito.mock(ReviewTimelineAppender.class),
            null,
            null,
            null,
            (TransactionTemplate) null,
            new ManualReviewIdempotencyCoordinator(org.mockito.Mockito.mock(ScheduledExecutorService.class)),
            org.mockito.Mockito.mock(ReviewTaskAfterCommitPublisher.class)
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("reviewTaskStateMachine");
    }
}
