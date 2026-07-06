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
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    public ReviewTaskDetailDataLoader(
        ChangedFileMapper changedFileMapper,
        ReviewFindingMapper reviewFindingMapper,
        ReviewTimelineQueryService timelineQueryService
    ) {
        this(changedFileMapper, reviewFindingMapper, timelineQueryService, new ReviewTaskDetailFindingAssembler());
    }

    public ReviewTaskDetailDataLoader(
        ChangedFileMapper changedFileMapper,
        ReviewFindingMapper reviewFindingMapper,
        ReviewTimelineQueryService timelineQueryService,
        ReviewTaskDetailFindingAssembler findingAssembler
    ) {
        this.changedFileMapper = changedFileMapper;
        this.reviewFindingMapper = reviewFindingMapper;
        this.timelineQueryService = timelineQueryService;
        this.findingAssembler = findingAssembler;
    }

    public ReviewTaskDetailData load(Long taskId) {
        PageResponse<ChangedFileDto> changedFiles = loadChangedFilesPage(taskId, 1, DETAIL_INITIAL_PAGE_SIZE, null);
        PageResponse<ReviewFindingDto> findings = loadFindingsPage(taskId, 1, DETAIL_INITIAL_PAGE_SIZE, null, null, null);
        PageResponse<MissingTestDto> missingTests = loadMissingTestsPage(taskId, 1, DETAIL_INITIAL_PAGE_SIZE);
        List<ReviewTimelineItem> timeline = loadTimelineItems(taskId, DETAIL_INITIAL_TIMELINE_LIMIT);
        FindingSeverityCountsDto findingSeverityCounts = reviewFindingMapper.selectFindingSeverityCounts(taskId);

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

    public List<ReviewTimelineItem> loadTimelineItems(Long taskId, int limit) {
        return timelineQueryService.loadLatestItemsByTaskId(taskId, limit);
    }

    public PageResponse<ChangedFileDto> loadChangedFilesPage(Long taskId, int page, int pageSize, Boolean hasFinding) {
        Page<ChangedFile> result = changedFileMapper.selectPage(
            Page.of(page, pageSize),
            changedFilePageQuery(taskId, hasFinding)
        );
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
        Page<ReviewFinding> result = reviewFindingMapper.selectPage(
            Page.of(page, pageSize),
            findingPageQuery(taskId, severity, category, feedbackStatus)
        );
        return new PageResponse<>(findingAssembler.toFindingDtos(pageRecords(result)), pageTotal(result));
    }

    public PageResponse<MissingTestDto> loadMissingTestsPage(Long taskId, int page, int pageSize) {
        Page<ReviewFinding> result = reviewFindingMapper.selectPage(
            Page.of(page, pageSize),
            new LambdaQueryWrapper<ReviewFinding>()
                .eq(ReviewFinding::getTaskId, taskId)
                .eq(ReviewFinding::getCategory, CATEGORY_MISSING_TEST)
                .orderByAsc(ReviewFinding::getId)
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

    private LambdaQueryWrapper<ChangedFile> changedFilePageQuery(Long taskId, Boolean hasFinding) {
        LambdaQueryWrapper<ChangedFile> wrapper = new LambdaQueryWrapper<ChangedFile>()
            .eq(ChangedFile::getTaskId, taskId)
            .orderByAsc(ChangedFile::getId);
        if (hasFinding == null) {
            return wrapper;
        }
        String findingFileSql = "select distinct file_path from review_finding where task_id = "
            + taskId
            + " and category = 'FINDING'";
        if (Boolean.TRUE.equals(hasFinding)) {
            return wrapper.inSql(ChangedFile::getFilePath, findingFileSql);
        }
        return wrapper.notInSql(ChangedFile::getFilePath, findingFileSql);
    }

    private LambdaQueryWrapper<ReviewFinding> findingPageQuery(
        Long taskId,
        String severity,
        String category,
        String feedbackStatus
    ) {
        LambdaQueryWrapper<ReviewFinding> wrapper = new LambdaQueryWrapper<ReviewFinding>()
            .eq(ReviewFinding::getTaskId, taskId)
            .eq(ReviewFinding::getCategory, CATEGORY_FINDING)
            .orderByAsc(ReviewFinding::getId);

        if (StringUtils.hasText(severity)) {
            wrapper.eq(ReviewFinding::getSeverity, normalizeUpper(severity));
        }
        if (StringUtils.hasText(category)) {
            wrapper.eq(ReviewFinding::getReviewDimension, normalizeUpper(category));
        }
        if (StringUtils.hasText(feedbackStatus)) {
            String normalizedStatus = normalizeUpper(feedbackStatus);
            if ("UNREVIEWED".equals(normalizedStatus)) {
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

    private String normalizeUpper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
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
