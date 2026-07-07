package com.repoguard.agent.timeline;

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
        insertTimeline(taskId, label, eventTime, ReviewTimelineStatus.CURRENT.code(), 1);
    }

    public void append(Long taskId, String label, LocalDateTime eventTime, String status) {
        insertTimeline(taskId, label, eventTime, status, nextSortOrder(taskId));
    }

    public void append(Long taskId, String label, LocalDateTime eventTime, ReviewTimelineStatus status) {
        append(taskId, label, eventTime, status.code());
    }

    public void completeCurrentAndAppend(Long taskId, String label, LocalDateTime eventTime, String status) {
        completeCurrentTimelines(taskId);
        insertTimeline(taskId, label, eventTime, status, nextSortOrder(taskId));
    }

    public void completeCurrentAndAppend(
        Long taskId,
        String label,
        LocalDateTime eventTime,
        ReviewTimelineStatus status
    ) {
        completeCurrentAndAppend(taskId, label, eventTime, status.code());
    }

    public void append(Long taskId, String label, LocalDateTime eventTime, String status, int minimumSortOrder) {
        completeCurrentTimelines(taskId);
        insertTimeline(taskId, label, eventTime, status, Math.max(minimumSortOrder, nextSortOrder(taskId)));
    }

    public void append(
        Long taskId,
        String label,
        LocalDateTime eventTime,
        ReviewTimelineStatus status,
        int minimumSortOrder
    ) {
        append(taskId, label, eventTime, status.code(), minimumSortOrder);
    }

    public void completeCurrentTimelines(Long taskId) {
        reviewTimelineMapper.update(
            new UpdateWrapper<ReviewTimeline>()
                .eq("task_id", taskId)
                .eq("status", ReviewTimelineStatus.CURRENT.code())
                .set("status", ReviewTimelineStatus.DONE.code())
        );
    }

    private void insertTimeline(Long taskId, String label, LocalDateTime eventTime, String status, int sortOrder) {
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
