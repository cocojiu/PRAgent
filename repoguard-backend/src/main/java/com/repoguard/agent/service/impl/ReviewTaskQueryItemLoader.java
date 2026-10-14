package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
        return timelineQueryService.loadLatestFailures(tasks);
    }

    public List<ReviewTimeline> loadTimelines(Long taskId) {
        return timelineQueryService.loadByTaskId(taskId);
    }

    public ReviewTimelineItem loadLatestTimelineItem(Long taskId) {
        return timelineQueryService.latestItem(timelineQueryService.loadLatestByTaskId(taskId, 1));
    }

    public ReviewTask loadStatusTask(Long taskId) {
        return reviewTaskMapper.selectOne(new LambdaQueryWrapper<ReviewTask>()
            .select(ReviewTask::getId, ReviewTask::getStatus, ReviewTask::getRiskLevel,
                ReviewTask::getLlmStatus, ReviewTask::getDurationSeconds, ReviewTask::getCreatedAt,
                ReviewTask::getStartedAt, ReviewTask::getFinishedAt, ReviewTask::getAssessmentStatus,
                ReviewTask::getHumanReviewRequired, ReviewTask::getHumanReviewStatus,
                ReviewTask::getHumanReviewNote, ReviewTask::getHumanReviewBy, ReviewTask::getHumanReviewedAt)
            .eq(ReviewTask::getId, taskId));
    }

    public ReviewTaskListItem assembleStatus(ReviewTask task) {
        return assemble(task, timelineQueryService.loadLatestFailures(List.of(task))
            .getOrDefault(task.getId(), List.of()));
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
