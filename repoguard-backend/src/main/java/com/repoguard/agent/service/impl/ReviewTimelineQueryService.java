package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.dto.ReviewTimelineItem;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.timeline.ReviewTimelineStatus;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ReviewTimelineQueryService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ReviewTimelineMapper reviewTimelineMapper;

    public ReviewTimelineQueryService(ReviewTimelineMapper reviewTimelineMapper) {
        this.reviewTimelineMapper = reviewTimelineMapper;
    }

    public Map<Long, List<ReviewTimeline>> loadByTaskId(List<ReviewTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> taskIds = tasks.stream()
            .map(ReviewTask::getId)
            .filter(id -> id != null)
            .toList();
        if (taskIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<ReviewTimeline> timelines = reviewTimelineMapper.selectList(
            new LambdaQueryWrapper<ReviewTimeline>()
                .in(ReviewTimeline::getTaskId, taskIds)
                .orderByAsc(ReviewTimeline::getTaskId)
                .orderByAsc(ReviewTimeline::getSortOrder)
        );
        if (timelines == null || timelines.isEmpty()) {
            return Collections.emptyMap();
        }
        return timelines.stream().collect(Collectors.groupingBy(ReviewTimeline::getTaskId));
    }

    public List<ReviewTimeline> loadByTaskId(Long taskId) {
        List<ReviewTimeline> timelines = reviewTimelineMapper.selectList(
            new LambdaQueryWrapper<ReviewTimeline>()
                .eq(ReviewTimeline::getTaskId, taskId)
                .orderByAsc(ReviewTimeline::getSortOrder)
        );
        return timelines == null ? List.of() : timelines;
    }

    public List<ReviewTimelineItem> loadItemsByTaskId(Long taskId) {
        return loadByTaskId(taskId).stream().map(this::toTimelineItem).toList();
    }

    public List<ReviewTimeline> loadLatestByTaskId(Long taskId, int limit) {
        int safeLimit = Math.min(100, Math.max(1, limit));
        List<ReviewTimeline> timelines = reviewTimelineMapper.selectList(
            new LambdaQueryWrapper<ReviewTimeline>()
                .eq(ReviewTimeline::getTaskId, taskId)
                .orderByDesc(ReviewTimeline::getSortOrder)
                .orderByDesc(ReviewTimeline::getId)
                .last("limit " + safeLimit)
        );
        if (timelines == null || timelines.isEmpty()) {
            return List.of();
        }
        List<ReviewTimeline> orderedTimelines = new ArrayList<>(timelines);
        Collections.reverse(orderedTimelines);
        return orderedTimelines;
    }

    public List<ReviewTimelineItem> loadLatestItemsByTaskId(Long taskId, int limit) {
        return loadLatestByTaskId(taskId, limit).stream().map(this::toTimelineItem).toList();
    }

    public ReviewTimelineItem latestItem(List<ReviewTimeline> timelines) {
        return timelines == null || timelines.isEmpty()
            ? null
            : toTimelineItem(timelines.getLast());
    }

    public List<String> labels(List<ReviewTimeline> timelines) {
        if (timelines == null || timelines.isEmpty()) {
            return List.of();
        }
        return timelines.stream().map(ReviewTimeline::getLabel).toList();
    }

    public List<String> itemLabels(List<ReviewTimelineItem> timelines) {
        if (timelines == null || timelines.isEmpty()) {
            return List.of();
        }
        return timelines.stream().map(ReviewTimelineItem::label).toList();
    }

    private ReviewTimelineItem toTimelineItem(ReviewTimeline timeline) {
        return new ReviewTimelineItem(
            timeline.getLabel(),
            timeline.getEventTime().format(TIME_FORMATTER),
            ReviewTimelineStatus.from(timeline.getStatus()).displayStatus()
        );
    }
}
