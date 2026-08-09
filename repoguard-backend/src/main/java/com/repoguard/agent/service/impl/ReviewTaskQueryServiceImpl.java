package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.CacheNames;
import com.repoguard.agent.dto.ChangedFileDto;
import com.repoguard.agent.dto.FindingSeverityCountsDto;
import com.repoguard.agent.dto.MissingTestDto;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.ReviewQuery;
import com.repoguard.agent.dto.ReviewFindingDto;
import com.repoguard.agent.dto.ReviewTaskListItem;
import com.repoguard.agent.dto.ReviewTaskListSummary;
import com.repoguard.agent.dto.ReviewTaskStatusResponse;
import com.repoguard.agent.dto.ReviewTaskSummary;
import com.repoguard.agent.dto.ReviewTimelineItem;
import com.repoguard.agent.entity.ReviewTaskArchiveSummary;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.mapper.ReviewTaskArchiveSummaryMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper.ReviewTaskListSummaryStat;
import com.repoguard.agent.review.ReviewRepositoryDimensionService;
import com.repoguard.agent.review.ReviewTaskCursorCodec;
import com.repoguard.agent.review.ReviewTaskDetailAssembler;
import com.repoguard.agent.service.ReviewTaskQueryService;
import com.repoguard.agent.service.impl.ReviewTaskDetailDataLoader.ReviewTaskDetailData;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ReviewTaskQueryServiceImpl implements ReviewTaskQueryService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTaskArchiveSummaryMapper archiveSummaryMapper;
    private final ReviewTaskDetailAssembler detailAssembler;
    private final ReviewTaskDetailDataLoader detailDataLoader;
    private final ReviewTaskQueryItemLoader queryItemLoader;
    private final ReviewTaskStatusAssembler statusAssembler;
    private final ReviewTaskListQueryBuilder listQueryBuilder;
    private final ReviewRepositoryDimensionService repositoryDimensionService;

    public ReviewTaskQueryServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTaskArchiveSummaryMapper archiveSummaryMapper,
        ReviewTaskDetailAssembler detailAssembler,
        ReviewTaskDetailDataLoader detailDataLoader,
        ReviewTaskQueryItemLoader queryItemLoader,
        ReviewTaskStatusAssembler statusAssembler,
        ReviewTaskListQueryBuilder listQueryBuilder,
        ReviewRepositoryDimensionService repositoryDimensionService
    ) {
        this.reviewTaskMapper = Objects.requireNonNull(reviewTaskMapper, "reviewTaskMapper must not be null");
        this.archiveSummaryMapper = Objects.requireNonNull(archiveSummaryMapper, "archiveSummaryMapper must not be null");
        this.detailAssembler = Objects.requireNonNull(detailAssembler, "detailAssembler must not be null");
        this.detailDataLoader = Objects.requireNonNull(detailDataLoader, "detailDataLoader must not be null");
        this.queryItemLoader = Objects.requireNonNull(queryItemLoader, "queryItemLoader must not be null");
        this.statusAssembler = Objects.requireNonNull(statusAssembler, "statusAssembler must not be null");
        this.listQueryBuilder = Objects.requireNonNull(listQueryBuilder, "listQueryBuilder must not be null");
        this.repositoryDimensionService = Objects.requireNonNull(
            repositoryDimensionService,
            "repositoryDimensionService must not be null"
        );
    }

    @Override
    public PageResponse<ReviewTaskListItem> listReviews(ReviewQuery query) {
        ReviewTaskCursorCodec.Cursor cursor = listQueryBuilder.decodeCursor(query);
        if (cursor != null) {
            return listReviewsByKeyset(query, cursor);
        }
        Page<ReviewTask> page = reviewTaskMapper.selectPage(
            Page.of(query.page(), query.pageSize()),
            listQueryBuilder.build(query)
        );
        List<ReviewTask> tasks = page.getRecords();
        Map<Long, List<ReviewTimeline>> timelinesByTaskId = queryItemLoader.loadTimelinesByTaskId(tasks);
        boolean hasMore = (long) query.page() * query.pageSize() < page.getTotal();
        return new PageResponse<>(
            tasks.stream()
                .map(task -> queryItemLoader.assemble(task, timelinesByTaskId.get(task.getId())))
                .toList(),
            page.getTotal(),
            hasMore ? nextCursor(query, tasks, page.getTotal()) : null,
            hasMore
        );
    }

    @Override
    @Cacheable(
        cacheNames = CacheNames.REVIEW_TASK_LIST_SUMMARY,
        key = "#query.listSummaryCacheKey()",
        sync = true
    )
    public ReviewTaskListSummary getReviewListSummary(ReviewQuery query) {
        ReviewTaskListSummaryStat stat = reviewTaskMapper.selectListSummaryStat(listQueryBuilder.buildCountQuery(query));
        if (stat == null) {
            return new ReviewTaskListSummary(0L, 0L, 0L, 0L);
        }
        return new ReviewTaskListSummary(
            longValue(stat.getTotal()),
            longValue(stat.getHighRisk()),
            longValue(stat.getFailed()),
            roundedSeconds(stat.getAverageDurationSeconds())
        );
    }

    private PageResponse<ReviewTaskListItem> listReviewsByKeyset(
        ReviewQuery query,
        ReviewTaskCursorCodec.Cursor cursor
    ) {
        List<ReviewTask> fetchedTasks = reviewTaskMapper.selectList(listQueryBuilder.buildKeysetPage(query, cursor));
        int pageSize = Math.max(1, Math.min(query.pageSize(), 100));
        boolean hasMore = fetchedTasks.size() > pageSize;
        List<ReviewTask> tasks = hasMore ? fetchedTasks.subList(0, pageSize) : fetchedTasks;
        Map<Long, List<ReviewTimeline>> timelinesByTaskId = queryItemLoader.loadTimelinesByTaskId(tasks);
        return new PageResponse<>(
            tasks.stream()
                .map(task -> queryItemLoader.assemble(task, timelinesByTaskId.get(task.getId())))
                .toList(),
            cursor.total(),
            hasMore ? nextCursor(query, tasks, cursor.total()) : null,
            hasMore
        );
    }

    private String nextCursor(ReviewQuery query, List<ReviewTask> tasks, long total) {
        if (tasks == null || tasks.isEmpty()) {
            return null;
        }
        ReviewTask lastTask = tasks.getLast();
        return listQueryBuilder.encodeCursor(query, lastTask.getCreatedAt(), lastTask.getId(), total);
    }

    @Override
    public List<String> listRepositories() {
        return repositoryDimensionService.listRepositoryLabels();
    }

    @Override
    public ReviewTaskSummary getReviewDetail(Long id) {
        ReviewTask task = reviewTaskMapper.selectById(id);
        if (task == null) {
            return getArchivedReviewDetail(id);
        }
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

    private ReviewTaskSummary getArchivedReviewDetail(Long id) {
        ReviewTaskArchiveSummary archive = archiveSummaryMapper.selectByTaskId(id);
        if (archive == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + id);
        }
        ReviewTask task = archivedTask(archive);
        ReviewTaskListItem item = archivedListItem(archive);
        return ReviewTaskSummary.fromDetail(
            detailAssembler.assemble(
                task,
                item,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                longValue(archive.getFindingCount()),
                longValue(archive.getMissingTestCount()),
                longValue(archive.getChangedFileCount()),
                FindingSeverityCountsDto.empty()
            ),
            true,
            archive.getCleanupBatchId(),
            archive.getBackupReference(),
            formatDateTimeOrNull(archive.getArchivedAt())
        );
    }

    private ReviewTask archivedTask(ReviewTaskArchiveSummary archive) {
        ReviewTask task = new ReviewTask();
        task.setId(archive.getTaskId());
        task.setPrNumber(archive.getPrNumber());
        task.setTitle(archive.getTitle());
        task.setRepository(archive.getRepository());
        task.setOrganization(archive.getOrganization());
        task.setCommitSha(archive.getCommitSha());
        task.setBranchName(archive.getBranchName());
        task.setStatus(archive.getStatus());
        task.setRiskLevel(archive.getRiskLevel());
        task.setAssessmentStatus(archive.getAssessmentStatus());
        task.setMqRetries(0);
        task.setLlmStatus(archive.getStatus());
        task.setSource(archive.getSource());
        task.setTriggerSource(archive.getTriggerSource());
        task.setCreatedAt(archive.getCreatedAt());
        task.setFinishedAt(archive.getFinishedAt());
        task.setDurationSeconds(archive.getDurationSeconds());
        task.setHumanReviewRequired(false);
        task.setHumanReviewStatus("NOT_REQUIRED");
        return task;
    }

    private ReviewTaskListItem archivedListItem(ReviewTaskArchiveSummary archive) {
        return new ReviewTaskListItem(
            archive.getTaskId(),
            archive.getPrNumber(),
            archive.getTitle(),
            archive.getRepository(),
            archive.getOrganization(),
            archive.getCommitSha(),
            archive.getBranchName(),
            lower(archive.getStatus()),
            lower(archive.getRiskLevel()),
            0,
            lower(archive.getStatus()),
            archive.getSource(),
            archive.getTriggerSource(),
            formatDateTimeOrNull(archive.getCreatedAt()),
            formatDuration(archive.getDurationSeconds()),
            null,
            null,
            null,
            false,
            "not_required",
            null,
            null,
            null,
            lower(archive.getAssessmentStatus())
        );
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
        PageResponse<ReviewFindingDto> result = detailDataLoader.loadFindingsPage(
            id,
            page,
            pageSize,
            severity,
            category,
            feedbackStatus
        );
        if (hasPageData(result)) {
            return result;
        }
        ReviewTaskArchiveSummary archive = archiveIfHotTaskMissing(id);
        if (archive != null) {
            long total = hasAnyText(severity, category, feedbackStatus) ? 0L : longValue(archive.getFindingCount());
            return new PageResponse<>(List.of(), total);
        }
        return result;
    }

    @Override
    public PageResponse<ChangedFileDto> listChangedFiles(Long id, int page, int pageSize, Boolean hasFinding) {
        PageResponse<ChangedFileDto> result = detailDataLoader.loadChangedFilesPage(id, page, pageSize, hasFinding);
        if (hasPageData(result)) {
            return result;
        }
        ReviewTaskArchiveSummary archive = archiveIfHotTaskMissing(id);
        if (archive != null) {
            long total = hasFinding == null ? longValue(archive.getChangedFileCount()) : 0L;
            return new PageResponse<>(List.of(), total);
        }
        return result;
    }

    @Override
    public PageResponse<MissingTestDto> listMissingTests(Long id, int page, int pageSize) {
        PageResponse<MissingTestDto> result = detailDataLoader.loadMissingTestsPage(id, page, pageSize);
        if (hasPageData(result)) {
            return result;
        }
        ReviewTaskArchiveSummary archive = archiveIfHotTaskMissing(id);
        if (archive != null) {
            return new PageResponse<>(List.of(), longValue(archive.getMissingTestCount()));
        }
        return result;
    }

    @Override
    public List<ReviewTimelineItem> listReviewTimeline(Long id, int limit) {
        List<ReviewTimelineItem> result = detailDataLoader.loadTimelineItems(id, limit);
        if (result != null && !result.isEmpty()) {
            return result;
        }
        ReviewTaskArchiveSummary archive = archiveIfHotTaskMissing(id);
        if (archive != null) {
            return List.of(archivedTimelineItem(archive));
        }
        return result == null ? List.of() : result;
    }

    @Override
    public ReviewTaskStatusResponse getReviewStatus(Long id) {
        ReviewTask task = reviewTaskMapper.selectById(id);
        if (task == null) {
            ReviewTaskArchiveSummary archive = loadArchiveSummaryOrThrow(id);
            return statusAssembler.assemble(
                archivedTask(archive),
                archivedListItem(archive),
                archivedTimelineItem(archive)
            );
        }
        List<ReviewTimeline> timelines = queryItemLoader.loadTimelines(id);
        var latestTimeline = queryItemLoader.latestTimelineItem(timelines);
        ReviewTaskListItem item = queryItemLoader.assemble(task, timelines);

        return statusAssembler.assemble(task, item, latestTimeline);
    }

    private ReviewTaskArchiveSummary archiveIfHotTaskMissing(Long id) {
        if (reviewTaskMapper.selectById(id) != null) {
            return null;
        }
        return loadArchiveSummaryOrThrow(id);
    }

    private boolean hasPageData(PageResponse<?> response) {
        return response != null
            && (response.total() > 0 || (response.items() != null && !response.items().isEmpty()));
    }

    private ReviewTaskArchiveSummary loadArchiveSummaryOrThrow(Long id) {
        ReviewTaskArchiveSummary archive = archiveSummaryMapper.selectByTaskId(id);
        if (archive == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + id);
        }
        return archive;
    }

    private ReviewTimelineItem archivedTimelineItem(ReviewTaskArchiveSummary archive) {
        return new ReviewTimelineItem(
            "Review task archived; summary restored from retention archive",
            formatDateTimeOrNull(archive.getArchivedAt()),
            "done"
        );
    }

    private String formatDateTimeOrNull(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME_FORMATTER);
    }

    private String formatDuration(Integer durationSeconds) {
        int totalSeconds = durationSeconds == null ? 0 : durationSeconds;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return minutes + " 分 " + seconds + " 秒";
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private long longValue(Integer value) {
        return value == null ? 0L : value.longValue();
    }

    private long longValue(Long value) {
        return value == null ? 0L : value;
    }

    private long roundedSeconds(BigDecimal value) {
        return value == null ? 0L : value.setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private boolean hasAnyText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return true;
            }
        }
        return false;
    }

}
