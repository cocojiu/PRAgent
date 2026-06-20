package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.ReviewQuery;
import com.repoguard.agent.dto.ReviewTaskDetail;
import com.repoguard.agent.dto.ReviewTaskListItem;
import com.repoguard.agent.dto.ReviewTaskStatusResponse;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.review.PrReviewSummaryBuilder;
import com.repoguard.agent.review.ReviewRiskProfileBuilder;
import com.repoguard.agent.review.ReviewTaskDetailAssembler;
import com.repoguard.agent.service.ReviewTaskQueryService;
import com.repoguard.agent.service.impl.ReviewTaskDetailDataLoader.ReviewTaskDetailData;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ReviewTaskQueryServiceImpl implements ReviewTaskQueryService {

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTaskDetailAssembler detailAssembler;
    private final ReviewTaskDetailDataLoader detailDataLoader;
    private final ReviewFailureSummaryResolver failureSummaryResolver;
    private final ReviewTimelineQueryService timelineQueryService;
    private final ReviewTaskStatusAssembler statusAssembler;
    private final ReviewTaskListItemAssembler listItemAssembler;
    private final ReviewTaskListQueryBuilder listQueryBuilder;

    public ReviewTaskQueryServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ChangedFileMapper changedFileMapper,
        ReviewFindingMapper reviewFindingMapper,
        ReviewTimelineMapper reviewTimelineMapper
    ) {
        this(
            reviewTaskMapper,
            changedFileMapper,
            reviewFindingMapper,
            reviewTimelineMapper,
            new ReviewTaskDetailAssembler(
                new ReviewRiskProfileBuilder(),
                new PrReviewSummaryBuilder()
            ),
            new ReviewTaskDetailDataLoader(
                changedFileMapper,
                reviewFindingMapper,
                new ReviewTimelineQueryService(reviewTimelineMapper)
            ),
            new ReviewFailureSummaryResolver(),
            new ReviewTimelineQueryService(reviewTimelineMapper),
            new ReviewTaskStatusAssembler(),
            new ReviewTaskListItemAssembler(),
            new ReviewTaskListQueryBuilder()
        );
    }

    @Autowired
    public ReviewTaskQueryServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ChangedFileMapper changedFileMapper,
        ReviewFindingMapper reviewFindingMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewTaskDetailAssembler detailAssembler,
        ReviewTaskDetailDataLoader detailDataLoader,
        ReviewFailureSummaryResolver failureSummaryResolver,
        ReviewTimelineQueryService timelineQueryService,
        ReviewTaskStatusAssembler statusAssembler,
        ReviewTaskListItemAssembler listItemAssembler,
        ReviewTaskListQueryBuilder listQueryBuilder
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.detailAssembler = detailAssembler;
        this.detailDataLoader = detailDataLoader;
        this.failureSummaryResolver = failureSummaryResolver;
        this.timelineQueryService = timelineQueryService;
        this.statusAssembler = statusAssembler;
        this.listItemAssembler = listItemAssembler;
        this.listQueryBuilder = listQueryBuilder;
    }

    @Override
    public PageResponse<ReviewTaskListItem> listReviews(ReviewQuery query) {
        Page<ReviewTask> page = reviewTaskMapper.selectPage(
            Page.of(query.page(), query.pageSize()),
            listQueryBuilder.build(query)
        );
        List<ReviewTask> tasks = page.getRecords();
        Map<Long, List<ReviewTimeline>> timelinesByTaskId = timelineQueryService.loadByTaskId(tasks);
        return new PageResponse<>(
            tasks.stream()
                .map(task -> listItemAssembler.assemble(task, failureSummaryResolver.resolve(task, timelineQueryService.labels(timelinesByTaskId.get(task.getId())))))
                .toList(),
            page.getTotal()
        );
    }

    @Override
    public ReviewTaskDetail getReviewDetail(Long id) {
        ReviewTask task = reviewTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + id);
        }

        ReviewTaskDetailData detailData = detailDataLoader.load(id);

        ReviewTaskListItem item = listItemAssembler.assemble(
            task,
            failureSummaryResolver.resolve(task, timelineQueryService.itemLabels(detailData.timeline()))
        );
        return detailAssembler.assemble(
            task,
            item,
            detailData.findings(),
            detailData.missingTests(),
            detailData.changedFiles(),
            detailData.timeline()
        );
    }

    @Override
    public ReviewTaskStatusResponse getReviewStatus(Long id) {
        ReviewTask task = reviewTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + id);
        }

        List<ReviewTimeline> timelines = timelineQueryService.loadByTaskId(id);
        var latestTimeline = timelineQueryService.latestItem(timelines);
        ReviewTaskListItem item = listItemAssembler.assemble(task, failureSummaryResolver.resolve(task, timelineQueryService.labels(timelines)));

        return statusAssembler.assemble(task, item, latestTimeline);
    }

}
