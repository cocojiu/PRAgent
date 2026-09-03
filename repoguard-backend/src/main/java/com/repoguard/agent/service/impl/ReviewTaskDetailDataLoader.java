package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.dto.ChangedFileDto;
import com.repoguard.agent.dto.FindingSeverityCountsDto;
import com.repoguard.agent.dto.MissingTestDto;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.ReviewFindingDto;
import com.repoguard.agent.dto.ReviewTimelineItem;
import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.projection.ReviewFindingProjections.ReviewTaskDetailSummary;
import com.repoguard.agent.review.FindingFeedbackStatus;
import com.repoguard.agent.review.ReviewFindingProjectionAssembler;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ReviewTaskDetailDataLoader {

    private static final int DETAIL_INITIAL_PAGE_SIZE = 20;
    private static final int DETAIL_INITIAL_TIMELINE_LIMIT = 20;
    private static final String CATEGORY_FINDING = "FINDING";
    private static final String CATEGORY_MISSING_TEST = "MISSING_TEST";

    private final ChangedFileMapper changedFileMapper;
    private final ReviewFindingMapper reviewFindingMapper;
    private final ReviewTimelineQueryService timelineQueryService;
    private final ReviewTaskDetailFindingAssembler findingAssembler;

    public ReviewTaskDetailDataLoader(
        ChangedFileMapper changedFileMapper,
        ReviewFindingMapper reviewFindingMapper,
        ReviewTimelineQueryService timelineQueryService,
        ReviewTaskDetailFindingAssembler findingAssembler
    ) {
        this.changedFileMapper = Objects.requireNonNull(changedFileMapper, "changedFileMapper must not be null");
        this.reviewFindingMapper = Objects.requireNonNull(reviewFindingMapper, "reviewFindingMapper must not be null");
        this.timelineQueryService = Objects.requireNonNull(timelineQueryService, "timelineQueryService must not be null");
        this.findingAssembler = Objects.requireNonNull(findingAssembler, "findingAssembler must not be null");
    }

    public ReviewTaskDetailData load(Long taskId) {
        PageResponse<ChangedFileDto> changedFiles = loadChangedFilesPage(taskId, 1, DETAIL_INITIAL_PAGE_SIZE, null);
        PageResponse<ReviewFindingDto> findings = loadFindingsPage(taskId, 1, DETAIL_INITIAL_PAGE_SIZE, null, null, null);
        PageResponse<MissingTestDto> missingTests = loadMissingTestsPage(taskId, 1, DETAIL_INITIAL_PAGE_SIZE);
        List<ReviewTimelineItem> timeline = loadTimelineItems(taskId, DETAIL_INITIAL_TIMELINE_LIMIT);
        FindingSeverityCountsDto findingSeverityCounts = ReviewFindingProjectionAssembler.toDto(
            reviewFindingMapper.selectFindingSeverityCounts(taskId)
        );

        return new ReviewTaskDetailData(
            changedFiles.items(),
            findings.items(),
            missingTests.items(),
            timeline,
            changedFiles.total(),
            findings.total(),
            missingTests.total(),
            findingSeverityCounts == null ? FindingSeverityCountsDto.empty() : findingSeverityCounts
        );
    }

    public ReviewTaskDetailData loadSummary(Long taskId) {
        ReviewTaskDetailSummary summary = reviewFindingMapper.selectReviewTaskDetailSummary(taskId);
        return new ReviewTaskDetailData(
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            longValue(summary == null ? null : summary.changedFileTotal()),
            longValue(summary == null ? null : summary.findingTotal()),
            longValue(summary == null ? null : summary.missingTestTotal()),
            summary == null
                ? FindingSeverityCountsDto.empty()
                : new FindingSeverityCountsDto(
                    summary.critical(),
                    summary.high(),
                    summary.medium(),
                    summary.low(),
                    summary.info()
                )
        );
    }

    public List<ReviewTimelineItem> loadTimelineItems(Long taskId, int limit) {
        return timelineQueryService.loadLatestItemsByTaskId(taskId, limit);
    }

    public PageResponse<ChangedFileDto> loadChangedFilesPage(Long taskId, int page, int pageSize, Boolean hasFinding) {
        Page<ChangedFile> result = changedFilePage(taskId, page, pageSize, hasFinding);
        return new PageResponse<>(findingAssembler.toChangedFileDtos(pageRecords(result)), pageTotal(result));
    }

    public PageResponse<ReviewFindingDto> loadFindingsPage(
        Long taskId,
        int page,
        int pageSize,
        String severity,
        String category,
        String feedbackStatus
    ) {
        return loadFindingsPage(taskId, page, pageSize, severity, category, feedbackStatus, null);
    }

    public PageResponse<ReviewFindingDto> loadFindingsPage(
        Long taskId,
        int page,
        int pageSize,
        String severity,
        String category,
        String feedbackStatus,
        String source
    ) {
        Page<ReviewFinding> result = reviewFindingMapper.selectPage(
            Page.of(page, pageSize),
            findingPageQuery(taskId, severity, category, feedbackStatus, source)
        );
        return new PageResponse<>(findingAssembler.toFindingDtos(pageRecords(result)), pageTotal(result));
    }

    public PageResponse<MissingTestDto> loadMissingTestsPage(Long taskId, int page, int pageSize) {
        Page<ReviewFinding> result = reviewFindingMapper.selectPage(
            Page.of(page, pageSize),
            missingTestPageQuery(taskId)
        );
        return new PageResponse<>(findingAssembler.toMissingTestDtos(pageRecords(result)), pageTotal(result));
    }

    private <T> List<T> pageRecords(Page<T> page) {
        if (page == null || page.getRecords() == null) {
            return List.of();
        }
        return page.getRecords();
    }

    private long pageTotal(Page<?> page) {
        return page == null ? 0L : page.getTotal();
    }

    private Page<ChangedFile> changedFilePage(Long taskId, int page, int pageSize, Boolean hasFinding) {
        Page<ChangedFile> pageRequest = Page.of(page, pageSize);
        if (hasFinding == null) {
            return changedFileMapper.selectPage(
                pageRequest,
                new LambdaQueryWrapper<ChangedFile>()
                    .eq(ChangedFile::getTaskId, taskId)
                    .eq(ChangedFile::getCurrentAttempt, true)
                    .orderByAsc(ChangedFile::getId)
            );
        }
        if (Boolean.TRUE.equals(hasFinding)) {
            return changedFileMapper.selectChangedFilesWithFindings(pageRequest, taskId);
        }
        return changedFileMapper.selectChangedFilesWithoutFindings(pageRequest, taskId);
    }

    private LambdaQueryWrapper<ReviewFinding> findingPageQuery(
        Long taskId,
        String severity,
        String category,
        String feedbackStatus,
        String source
    ) {
        LambdaQueryWrapper<ReviewFinding> wrapper = new LambdaQueryWrapper<ReviewFinding>()
            .eq(ReviewFinding::getTaskId, taskId)
            .eq(ReviewFinding::getCurrentAttempt, true)
            .eq(ReviewFinding::getCategory, CATEGORY_FINDING)
            .orderByAsc(ReviewFinding::getId);

        if (StringUtils.hasText(severity)) {
            wrapper.eq(ReviewFinding::getSeverity, normalizeUpper(severity));
        }
        if (StringUtils.hasText(category)) {
            wrapper.eq(ReviewFinding::getReviewDimension, normalizeUpper(category));
        }
        if (StringUtils.hasText(source)) {
            wrapper.eq(ReviewFinding::getSource, normalizeUpper(source));
        }
        if (StringUtils.hasText(feedbackStatus)) {
            String normalizedStatus = FindingFeedbackStatus.queryCode(feedbackStatus);
            if (FindingFeedbackStatus.UNREVIEWED.code().equals(normalizedStatus)) {
                wrapper.and(query -> query
                    .isNull(ReviewFinding::getFeedbackStatus)
                    .or()
                    .eq(ReviewFinding::getFeedbackStatus, "")
                    .or()
                    .eq(ReviewFinding::getFeedbackStatus, normalizedStatus)
                );
            } else {
                wrapper.eq(ReviewFinding::getFeedbackStatus, normalizedStatus);
            }
        }
        return wrapper;
    }

    private LambdaQueryWrapper<ReviewFinding> missingTestPageQuery(Long taskId) {
        return new LambdaQueryWrapper<ReviewFinding>()
            .eq(ReviewFinding::getTaskId, taskId)
            .eq(ReviewFinding::getCurrentAttempt, true)
            .eq(ReviewFinding::getCategory, CATEGORY_MISSING_TEST)
            .orderByAsc(ReviewFinding::getId);
    }

    private String normalizeUpper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private long longValue(Long value) {
        return value == null ? 0L : value;
    }

    public record ReviewTaskDetailData(
        List<ChangedFileDto> changedFiles,
        List<ReviewFindingDto> findings,
        List<MissingTestDto> missingTests,
        List<ReviewTimelineItem> timeline,
        long changedFileTotal,
        long findingTotal,
        long missingTestTotal,
        FindingSeverityCountsDto findingSeverityCounts
    ) {
    }
}
