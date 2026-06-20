package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReviewTimelineAppenderTest {

    private final ReviewTimelineMapper reviewTimelineMapper = org.mockito.Mockito.mock(ReviewTimelineMapper.class);
    private final ReviewTimelineAppender appender = new ReviewTimelineAppender(reviewTimelineMapper);

    @Test
    void closesCurrentTimelineAndAppendsNewItem() {
        LocalDateTime eventTime = LocalDateTime.of(2026, 6, 20, 10, 30);

        appender.append(42L, "Review started", eventTime, "CURRENT", 2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<ReviewTimeline>> updateCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(reviewTimelineMapper).update(updateCaptor.capture());
        assertThat(updateCaptor.getValue().getSqlSet()).contains("status");

        ArgumentCaptor<ReviewTimeline> timelineCaptor = ArgumentCaptor.forClass(ReviewTimeline.class);
        verify(reviewTimelineMapper).insert(timelineCaptor.capture());
        ReviewTimeline timeline = timelineCaptor.getValue();
        assertThat(timeline.getTaskId()).isEqualTo(42L);
        assertThat(timeline.getLabel()).isEqualTo("Review started");
        assertThat(timeline.getEventTime()).isEqualTo(eventTime);
        assertThat(timeline.getStatus()).isEqualTo("CURRENT");
        assertThat(timeline.getSortOrder()).isEqualTo(2);
    }

    @Test
    void advancesSortOrderAfterLatestTimeline() {
        ReviewTimeline latest = new ReviewTimeline();
        latest.setSortOrder(7);
        when(reviewTimelineMapper.selectOne(any())).thenReturn(latest);

        appender.append(42L, "Review completed", LocalDateTime.now(), "DONE", 5);

        ArgumentCaptor<ReviewTimeline> timelineCaptor = ArgumentCaptor.forClass(ReviewTimeline.class);
        verify(reviewTimelineMapper).insert(timelineCaptor.capture());
        assertThat(timelineCaptor.getValue().getSortOrder()).isEqualTo(8);
    }
}
