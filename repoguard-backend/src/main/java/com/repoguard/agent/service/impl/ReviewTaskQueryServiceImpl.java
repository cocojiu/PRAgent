package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.dto.ChangedFileDto;
import com.repoguard.agent.dto.MissingTestDto;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.ReviewQuery;
import com.repoguard.agent.dto.ReviewFindingDto;
import com.repoguard.agent.dto.ReviewTaskListItem;
import com.repoguard.agent.dto.ReviewTaskStatusResponse;
import com.repoguard.agent.dto.ReviewTaskSummary;
import com.repoguard.agent.dto.ReviewTimelineItem;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.ReviewTaskDetailAssembler;
import com.repoguard.agent.service.ReviewTaskQueryService;
import com.repoguard.agent.service.impl.ReviewTaskDetailDataLoader.ReviewTaskDetailData;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class ReviewTaskQueryServiceImpl implements ReviewTaskQueryService {

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTaskDetailAssembler detailAssembler;
    private final ReviewTaskDetailDataLoader detailDataLoader;
    private final ReviewTaskQueryItemLoader queryItemLoader;
    private final ReviewTaskStatusAssembler statusAssembler;
    private final ReviewTaskListQueryBuilder listQueryBuilder;

    public ReviewTaskQueryServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTaskDetailAssembler detailAssembler,
        ReviewTaskDetailDataLoader detailDataLoader,
        ReviewTaskQueryItemLoader queryItemLoader,
        ReviewTaskStatusAssembler statusAssembler,
        ReviewTaskListQueryBuilder listQueryBuilder
    ) {
        this.reviewTaskMapper = Objects.requireNonNull(reviewTaskMapper, "reviewTaskMapper must not be null");
        this.detailAssembler = Objects.requireNonNull(detailAssembler, "detailAssembler must not be null");
        this.detailDataLoader = Objects.requireNonNull(detailDataLoader, "detailDataLoader must not be null");
        this.queryItemLoader = Objects.requireNonNull(queryItemLoader, "queryItemLoader must not be null");
        this.statusAssembler = Objects.requireNonNull(statusAssembler, "statusAssembler must not be null");
        this.listQueryBuilder = Objects.requireNonNull(listQueryBuilder, "listQueryBuilder must not be null");
    }

    @Override
    public PageResponse<ReviewTaskListItem> listReviews(ReviewQuery query) {
        Page<ReviewTask> page = reviewTaskMapper.selectPage(
            Page.of(query.page(), query.pageSize()),
            listQueryBuilder.build(query)
        );
        List<ReviewTask> tasks = page.getRecords();
        Map<Long, List<ReviewTimeline>> timelinesByTaskId = queryItemLoader.loadTimelinesByTaskId(tasks);
        return new PageResponse<>(
            tasks.stream()
                .map(task -> queryItemLoader.assemble(task, timelinesByTaskId.get(task.getId())))
                .toList(),
            page.getTotal()
        );
    }

    @Override
    public List<String> listRepositories() {
        List<String> repositories = reviewTaskMapper.selectDistinctRepositories();
        return repositories == null ? List.of() : repositories;
    }

    @Override
    public ReviewTaskSummary getReviewDetail(Long id) {
        ReviewTask task = queryItemLoader.loadRequired(id);
        ReviewTaskDetailData detailData = detailDataLoader.loadSummary(id);
        ReviewTaskListItem item = queryItemLoader.assembleFromTimelineItems(
            task,
            detailDataLoader.loadTimelineItems(id, 20)
        );
        return ReviewTaskSummary.fromDetail(detailAssembler.assemble(
            task,
            item,
            detailData.findings(),
            detailData.missingTests(),
            detailData.changedFiles(),
            detailData.timeline(),
            detailData.findingTotal(),
            detailData.missingTestTotal(),
            detailData.changedFileTotal(),
            detailData.findingSeverityCounts()
        ));
    }

    @Override
    public PageResponse<ReviewFindingDto> listReviewFindings(
        Long id,
        int page,
        int pageSize,
        String severity,
        String category,
        String feedbackStatus
    ) {
        queryItemLoader.loadRequired(id);
        return detailDataLoader.loadFindingsPage(id, page, pageSize, severity, category, feedbackStatus);
    }

    @Override
    public PageResponse<ChangedFileDto> listChangedFiles(Long id, int page, int pageSize, Boolean hasFinding) {
        queryItemLoader.loadRequired(id);
        return detailDataLoader.loadChangedFilesPage(id, page, pageSize, hasFinding);
    }

    @Override
    public PageResponse<MissingTestDto> listMissingTests(Long id, int page, int pageSize) {
        queryItemLoader.loadRequired(id);
        return detailDataLoader.loadMissingTestsPage(id, page, pageSize);
    }

    @Override
    public List<ReviewTimelineItem> listReviewTimeline(Long id, int limit) {
        queryItemLoader.loadRequired(id);
        return detailDataLoader.loadTimelineItems(id, limit);
    }

    @Override
    public ReviewTaskStatusResponse getReviewStatus(Long id) {
        ReviewTask task = queryItemLoader.loadRequired(id);
        List<ReviewTimeline> timelines = queryItemLoader.loadTimelines(id);
        var latestTimeline = queryItemLoader.latestTimelineItem(timelines);
        ReviewTaskListItem item = queryItemLoader.assemble(task, timelines);

        return statusAssembler.assemble(task, item, latestTimeline);
    }

}
