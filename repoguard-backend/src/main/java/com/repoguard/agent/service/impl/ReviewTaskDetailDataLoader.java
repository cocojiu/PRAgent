package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.dto.ChangedFileDto;
import com.repoguard.agent.dto.MissingTestDto;
import com.repoguard.agent.dto.ReviewFindingDto;
import com.repoguard.agent.dto.ReviewTimelineItem;
import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ReviewTaskDetailDataLoader {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String FEEDBACK_UNREVIEWED = "UNREVIEWED";

    private final ChangedFileMapper changedFileMapper;
    private final ReviewFindingMapper reviewFindingMapper;
    private final ReviewTimelineQueryService timelineQueryService;

    public ReviewTaskDetailDataLoader(
        ChangedFileMapper changedFileMapper,
        ReviewFindingMapper reviewFindingMapper,
        ReviewTimelineQueryService timelineQueryService
    ) {
        this.changedFileMapper = changedFileMapper;
        this.reviewFindingMapper = reviewFindingMapper;
        this.timelineQueryService = timelineQueryService;
    }

    public ReviewTaskDetailData load(Long taskId) {
        List<ChangedFileDto> changedFiles = changedFileMapper.selectList(
            new LambdaQueryWrapper<ChangedFile>()
                .eq(ChangedFile::getTaskId, taskId)
                .orderByAsc(ChangedFile::getId)
        ).stream().map(this::toChangedFileDto).toList();

        List<ReviewFinding> findings = reviewFindingMapper.selectList(
            new LambdaQueryWrapper<ReviewFinding>()
                .eq(ReviewFinding::getTaskId, taskId)
                .orderByAsc(ReviewFinding::getId)
        );

        List<ReviewFindingDto> findingDtos = findings.stream()
            .filter(finding -> "FINDING".equals(finding.getCategory()))
            .map(this::toFindingDto)
            .toList();
        List<MissingTestDto> missingTests = findings.stream()
            .filter(finding -> "MISSING_TEST".equals(finding.getCategory()))
            .map(this::toMissingTestDto)
            .toList();
        var timeline = timelineQueryService.loadItemsByTaskId(taskId);

        return new ReviewTaskDetailData(changedFiles, findingDtos, missingTests, timeline);
    }

    private ChangedFileDto toChangedFileDto(ChangedFile file) {
        return new ChangedFileDto(file.getFilePath(), file.getChangeType(), file.getAdditions(), file.getDeletions());
    }

    private ReviewFindingDto toFindingDto(ReviewFinding finding) {
        return new ReviewFindingDto(
            finding.getId(),
            lower(finding.getSeverity()),
            finding.getFilePath(),
            finding.getLineNumber(),
            finding.getMessage(),
            finding.getRecommendation(),
            lower(resolveFindingFeedbackStatus(finding)),
            finding.getFeedbackNote(),
            finding.getFeedbackBy(),
            formatDateTimeOrNull(finding.getFeedbackAt())
        );
    }

    private MissingTestDto toMissingTestDto(ReviewFinding finding) {
        return new MissingTestDto(
            finding.getFilePath(),
            finding.getMethodName(),
            finding.getTestType(),
            finding.getRecommendation()
        );
    }

    private String resolveFindingFeedbackStatus(ReviewFinding finding) {
        return StringUtils.hasText(finding.getFeedbackStatus()) ? finding.getFeedbackStatus() : FEEDBACK_UNREVIEWED;
    }

    private String formatDateTimeOrNull(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME_FORMATTER);
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    public record ReviewTaskDetailData(
        List<ChangedFileDto> changedFiles,
        List<ReviewFindingDto> findings,
        List<MissingTestDto> missingTests,
        List<ReviewTimelineItem> timeline
    ) {
    }
}
