package com.repoguard.agent.timeline;

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
        when(reviewTimelineMapper.selectOne(any())).thenReturn(latest);
        LocalDateTime eventTime = LocalDateTime.of(2026, 7, 6, 19, 5);

        appender.completeCurrentAndAppend(42L, "Retry queued", eventTime, "CURRENT");

        verifyCurrentTimelineClosed();
        ReviewTimeline inserted = insertedTimeline();
        assertThat(inserted.getLabel()).isEqualTo("Retry queued");
        assertThat(inserted.getStatus()).isEqualTo("CURRENT");
        assertThat(inserted.getSortOrder()).isEqualTo(4);
    }

    @Test
    void appendWithMinimumSortOrderClosesCurrentTimelineAndUsesMinimumWhenItIsLater() {
        LocalDateTime eventTime = LocalDateTime.of(2026, 7, 6, 19, 10);

        appender.append(42L, "Review started", eventTime, "CURRENT", 2);

        verifyCurrentTimelineClosed();
        ReviewTimeline inserted = insertedTimeline();
        assertThat(inserted.getLabel()).isEqualTo("Review started");
        assertThat(inserted.getStatus()).isEqualTo("CURRENT");
        assertThat(inserted.getSortOrder()).isEqualTo(2);
    }

    @Test
    void appendWithMinimumSortOrderAdvancesAfterLatestTimeline() {
        ReviewTimeline latest = new ReviewTimeline();
        latest.setSortOrder(7);
        when(reviewTimelineMapper.selectOne(any())).thenReturn(latest);

        appender.append(42L, "Review completed", LocalDateTime.now(), "DONE", 5);

        assertThat(insertedTimeline().getSortOrder()).isEqualTo(8);
    }

    @Test
    void appendWithoutClosingCurrentUsesNextSortOrderOnly() {
        ReviewTimeline latest = new ReviewTimeline();
        latest.setSortOrder(9);
        when(reviewTimelineMapper.selectOne(any())).thenReturn(latest);

        appender.append(42L, "Message publish recovered", LocalDateTime.now(), "CURRENT");

        ReviewTimeline inserted = insertedTimeline();
        assertThat(inserted.getLabel()).isEqualTo("Message publish recovered");
        assertThat(inserted.getStatus()).isEqualTo("CURRENT");
        assertThat(inserted.getSortOrder()).isEqualTo(10);
    }

    private void verifyCurrentTimelineClosed() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<ReviewTimeline>> updateCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(reviewTimelineMapper).update(updateCaptor.capture());
        assertThat(updateCaptor.getValue().getSqlSet()).contains("status");
    }

    private ReviewTimeline insertedTimeline() {
        ArgumentCaptor<ReviewTimeline> captor = ArgumentCaptor.forClass(ReviewTimeline.class);
        verify(reviewTimelineMapper).insert(captor.capture());
        return captor.getValue();
    }
}
