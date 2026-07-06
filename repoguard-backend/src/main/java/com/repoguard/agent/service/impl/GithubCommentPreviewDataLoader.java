package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.dto.ChangedFileDto;
import com.repoguard.agent.dto.FindingSeverityCountsDto;
import com.repoguard.agent.dto.GithubCommentPreviewFindingStat;
import com.repoguard.agent.dto.MissingTestDto;
import com.repoguard.agent.dto.ReviewFindingDto;
import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class GithubCommentPreviewDataLoader {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String FEEDBACK_UNREVIEWED = "UNREVIEWED";

    private final ChangedFileMapper changedFileMapper;
    private final ReviewFindingMapper reviewFindingMapper;

    public GithubCommentPreviewDataLoader(
        ChangedFileMapper changedFileMapper,
        ReviewFindingMapper reviewFindingMapper
    ) {
        this.changedFileMapper = changedFileMapper;
        this.reviewFindingMapper = reviewFindingMapper;
    }

    public GithubCommentPreviewData load(Long taskId) {
        Map<String, ChangedFile> changedFileByPath = changedFileMapper.selectList(
            new LambdaQueryWrapper<ChangedFile>()
                .eq(ChangedFile::getTaskId, taskId)
                .orderByAsc(ChangedFile::getId)
        ).stream().collect(Collectors.toMap(
            ChangedFile::getFilePath,
            Function.identity(),
            (first, ignored) -> first
        ));

        List<ReviewFinding> findings = reviewFindingMapper.selectList(
            new LambdaQueryWrapper<ReviewFinding>()
                .eq(ReviewFinding::getTaskId, taskId)
                .orderByAsc(ReviewFinding::getId)
        );
        List<ReviewFinding> actionableFindings = findings.stream()
            .filter(finding -> "FINDING".equals(finding.getCategory()))
            .toList();
        List<ReviewFindingDto> findingDtos = actionableFindings.stream().map(this::toFindingDto).toList();
        List<MissingTestDto> missingTests = findings.stream()
            .filter(finding -> "MISSING_TEST".equals(finding.getCategory()))
            .map(this::toMissingTestDto)
            .toList();
        List<ChangedFileDto> changedFileDtos = changedFileByPath.values().stream()
            .sorted(Comparator.comparing(file -> file.getId() == null ? Long.MAX_VALUE : file.getId()))
            .map(this::toChangedFileDto)
            .toList();

        return new GithubCommentPreviewData(
            changedFileByPath,
            actionableFindings,
            findingDtos,
            missingTests,
            changedFileDtos
        );
    }

    public GithubCommentPreviewPageData loadPage(
        Long taskId,
        long findingOffset,
        int findingLimit,
        boolean commentableOnly
    ) {
        GithubCommentPreviewFindingStat findingStat = reviewFindingMapper.selectGithubCommentPreviewFindingStat(taskId);
        List<ReviewFinding> pageFindings = loadPageFindings(taskId, findingOffset, findingLimit, commentableOnly);
        Map<String, ChangedFile> changedFileByPath = loadChangedFilesForFindings(taskId, pageFindings);
        List<ReviewFindingDto> findingDtos = pageFindings.stream().map(this::toFindingDto).toList();
        List<ChangedFileDto> focusChangedFiles = loadFocusChangedFiles(taskId);
        FindingSeverityCountsDto severityCounts = reviewFindingMapper.selectFindingSeverityCounts(taskId);

        return new GithubCommentPreviewPageData(
            changedFileByPath,
            pageFindings,
            findingDtos,
            focusChangedFiles,
            findingStat == null ? 0L : findingStat.totalFindingsOrZero(),
            findingStat == null ? 0L : findingStat.commentableFindingsOrZero(),
            findingStat == null ? 0L : findingStat.publishedFindingsOrZero(),
            countMissingTests(taskId),
            countChangedFiles(taskId),
            severityCounts == null ? FindingSeverityCountsDto.empty() : severityCounts
        );
    }

    private List<ReviewFinding> loadPageFindings(
        Long taskId,
        long findingOffset,
        int findingLimit,
        boolean commentableOnly
    ) {
        if (findingLimit <= 0) {
            return List.of();
        }
        List<ReviewFinding> findings = commentableOnly
            ? reviewFindingMapper.selectGithubCommentPreviewCommentableFindings(taskId, findingOffset, findingLimit)
            : reviewFindingMapper.selectGithubCommentPreviewFindings(taskId, findingOffset, findingLimit);
        return findings == null ? List.of() : findings;
    }

    private Map<String, ChangedFile> loadChangedFilesForFindings(Long taskId, List<ReviewFinding> findings) {
        List<String> paths = findings.stream()
            .map(ReviewFinding::getFilePath)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
        if (paths.isEmpty()) {
            return Collections.emptyMap();
        }
        List<ChangedFile> files = changedFileMapper.selectList(
            new LambdaQueryWrapper<ChangedFile>()
                .eq(ChangedFile::getTaskId, taskId)
                .in(ChangedFile::getFilePath, paths)
                .orderByAsc(ChangedFile::getId)
        );
        if (files == null || files.isEmpty()) {
            return Collections.emptyMap();
        }
        return files.stream().collect(Collectors.toMap(
            ChangedFile::getFilePath,
            Function.identity(),
            (first, ignored) -> first
        ));
    }

    private List<ChangedFileDto> loadFocusChangedFiles(Long taskId) {
        List<ChangedFile> files = changedFileMapper.selectTopChangedFilesByChurn(taskId, 3);
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        return files.stream().map(this::toChangedFileDto).toList();
    }

    private long countChangedFiles(Long taskId) {
        Long total = changedFileMapper.selectCount(
            new LambdaQueryWrapper<ChangedFile>().eq(ChangedFile::getTaskId, taskId)
        );
        return total == null ? 0L : total;
    }

    private long countMissingTests(Long taskId) {
        Long total = reviewFindingMapper.selectCount(
            new LambdaQueryWrapper<ReviewFinding>()
                .eq(ReviewFinding::getTaskId, taskId)
                .eq(ReviewFinding::getCategory, "MISSING_TEST")
        );
        return total == null ? 0L : total;
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
            defaultString(finding.getConfidence()),
            defaultString(finding.getEvidence()),
            defaultString(finding.getImpact()),
            defaultString(finding.getFixExample()),
            Boolean.TRUE.equals(finding.getIsBlocking()),
            defaultString(finding.getReviewDimension()),
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

    private String defaultString(String value) {
        return StringUtils.hasText(value) ? value : "";
    }

    public record GithubCommentPreviewData(
        Map<String, ChangedFile> changedFileByPath,
        List<ReviewFinding> actionableFindings,
        List<ReviewFindingDto> findings,
        List<MissingTestDto> missingTests,
        List<ChangedFileDto> changedFiles
    ) {
    }

    public record GithubCommentPreviewPageData(
        Map<String, ChangedFile> changedFileByPath,
        List<ReviewFinding> pageFindings,
        List<ReviewFindingDto> findings,
        List<ChangedFileDto> focusChangedFiles,
        long findingTotal,
        long commentableFindingCount,
        long publishedFindingCount,
        long missingTestTotal,
        long changedFileTotal,
        FindingSeverityCountsDto severityCounts
    ) {
    }
}
