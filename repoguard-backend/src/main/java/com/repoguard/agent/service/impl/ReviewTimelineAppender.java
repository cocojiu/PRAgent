package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
public class ReviewTimelineAppender {

    private final ReviewTimelineMapper reviewTimelineMapper;

    public ReviewTimelineAppender(ReviewTimelineMapper reviewTimelineMapper) {
        this.reviewTimelineMapper = reviewTimelineMapper;
    }

    public void appendInitial(Long taskId, String label, LocalDateTime eventTime) {
        append(taskId, label, eventTime, "CURRENT", 1);
    }

    public void completeCurrentAndAppend(Long taskId, String label, LocalDateTime eventTime, String status) {
        reviewTimelineMapper.update(
            new UpdateWrapper<ReviewTimeline>()
                .eq("task_id", taskId)
                .eq("status", "CURRENT")
                .set("status", "DONE")
        );
        append(taskId, label, eventTime, status, nextSortOrder(taskId));
    }

    private void append(Long taskId, String label, LocalDateTime eventTime, String status, int sortOrder) {
        ReviewTimeline timeline = new ReviewTimeline();
        timeline.setTaskId(taskId);
        timeline.setLabel(label);
        timeline.setEventTime(eventTime);
        timeline.setStatus(status);
        timeline.setSortOrder(sortOrder);
        reviewTimelineMapper.insert(timeline);
    }

    private int nextSortOrder(Long taskId) {
        ReviewTimeline latest = reviewTimelineMapper.selectOne(
            new LambdaQueryWrapper<ReviewTimeline>()
                .eq(ReviewTimeline::getTaskId, taskId)
                .orderByDesc(ReviewTimeline::getSortOrder)
                .last("limit 1")
        );
        return latest == null || latest.getSortOrder() == null ? 1 : latest.getSortOrder() + 1;
    }
}
