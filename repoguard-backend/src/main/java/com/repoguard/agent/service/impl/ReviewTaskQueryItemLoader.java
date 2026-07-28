package com.repoguard.agent.service.impl;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.ReviewTaskListItem;
import com.repoguard.agent.dto.ReviewTimelineItem;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.task.ReviewFailureSummaryResolver;
import com.repoguard.agent.review.task.ReviewTaskListItemAssembler;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class ReviewTaskQueryItemLoader {

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewFailureSummaryResolver failureSummaryResolver;
    private final ReviewTimelineQueryService timelineQueryService;
    private final ReviewTaskListItemAssembler listItemAssembler;

    public ReviewTaskQueryItemLoader(
        ReviewTaskMapper reviewTaskMapper,
        ReviewFailureSummaryResolver failureSummaryResolver,
        ReviewTimelineQueryService timelineQueryService,
        ReviewTaskListItemAssembler listItemAssembler
    ) {
        this.reviewTaskMapper = Objects.requireNonNull(reviewTaskMapper, "reviewTaskMapper must not be null");
        this.failureSummaryResolver =
            Objects.requireNonNull(failureSummaryResolver, "failureSummaryResolver must not be null");
        this.timelineQueryService = Objects.requireNonNull(timelineQueryService, "timelineQueryService must not be null");
        this.listItemAssembler = Objects.requireNonNull(listItemAssembler, "listItemAssembler must not be null");
    }

    public ReviewTask loadRequired(Long id) {
        ReviewTask task = reviewTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + id);
        }
        return task;
    }

    public Map<Long, List<ReviewTimeline>> loadTimelinesByTaskId(List<ReviewTask> tasks) {
        return timelineQueryService.loadByTaskId(tasks);
    }

    public List<ReviewTimeline> loadTimelines(Long taskId) {
        return timelineQueryService.loadByTaskId(taskId);
    }

    public ReviewTimelineItem latestTimelineItem(List<ReviewTimeline> timelines) {
        return timelineQueryService.latestItem(timelines);
    }

    public ReviewTaskListItem assemble(ReviewTask task, List<ReviewTimeline> timelines) {
        return listItemAssembler.assemble(
            task,
            failureSummaryResolver.resolve(task, timelineQueryService.labels(timelines))
        );
    }

    public ReviewTaskListItem assembleFromTimelineItems(ReviewTask task, List<ReviewTimelineItem> timelineItems) {
        return listItemAssembler.assemble(
            task,
            failureSummaryResolver.resolve(task, timelineQueryService.itemLabels(timelineItems))
        );
    }
}
