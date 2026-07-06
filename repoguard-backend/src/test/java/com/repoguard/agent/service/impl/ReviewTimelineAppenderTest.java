package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReviewTimelineAppenderTest {

    private final ReviewTimelineMapper reviewTimelineMapper = mock(ReviewTimelineMapper.class);
    private final ReviewTimelineAppender appender = new ReviewTimelineAppender(reviewTimelineMapper);

    @Test
    void appendInitialCreatesCurrentTimelineWithFirstSortOrder() {
        LocalDateTime eventTime = LocalDateTime.of(2026, 7, 6, 19, 0);

        appender.appendInitial(42L, "Task queued", eventTime);

        ReviewTimeline inserted = insertedTimeline();
        assertThat(inserted.getTaskId()).isEqualTo(42L);
        assertThat(inserted.getLabel()).isEqualTo("Task queued");
        assertThat(inserted.getEventTime()).isEqualTo(eventTime);
        assertThat(inserted.getStatus()).isEqualTo("CURRENT");
        assertThat(inserted.getSortOrder()).isEqualTo(1);
    }

    @Test
    void completeCurrentAndAppendMarksCurrentDoneAndUsesNextSortOrder() {
        ReviewTimeline latest = new ReviewTimeline();
        latest.setSortOrder(3);
        when(reviewTimelineMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(latest);
        LocalDateTime eventTime = LocalDateTime.of(2026, 7, 6, 19, 5);

        appender.completeCurrentAndAppend(42L, "Retry queued", eventTime, "CURRENT");

        verify(reviewTimelineMapper).update(any(UpdateWrapper.class));
        ReviewTimeline inserted = insertedTimeline();
        assertThat(inserted.getTaskId()).isEqualTo(42L);
        assertThat(inserted.getLabel()).isEqualTo("Retry queued");
        assertThat(inserted.getEventTime()).isEqualTo(eventTime);
        assertThat(inserted.getStatus()).isEqualTo("CURRENT");
        assertThat(inserted.getSortOrder()).isEqualTo(4);
    }

    private ReviewTimeline insertedTimeline() {
        ArgumentCaptor<ReviewTimeline> captor = ArgumentCaptor.forClass(ReviewTimeline.class);
        verify(reviewTimelineMapper).insert(captor.capture());
        return captor.getValue();
    }
}
