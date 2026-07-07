package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.dto.ChangedFileDto;
import com.repoguard.agent.dto.FindingSeverityCountsDto;
import com.repoguard.agent.dto.GithubCommentPreviewFindingStat;
import com.repoguard.agent.dto.GithubCommentPreviewItem;
import com.repoguard.agent.dto.PrRiskProfileDto;
import com.repoguard.agent.dto.PrReviewSummaryDto;
import com.repoguard.agent.dto.ReviewTaskListItem;
import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.GithubCommentPublication;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.review.PrReviewSummaryBuilder;
import com.repoguard.agent.review.ReviewRiskProfileBuilder;
import com.repoguard.agent.service.impl.GithubCommentPreviewPublicationLoader.GithubCommentPreviewPublicationData;
import com.repoguard.agent.service.impl.ReviewFailureSummaryResolver.ReviewFailureSummary;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class GithubCommentPublishCandidateLoader {

    private static final int PR_SUMMARY_FOCUS_FILE_LIMIT = 3;
    private static final ReviewFailureSummary NO_FAILURE_SUMMARY = new ReviewFailureSummary(null, null, null);

    private final ChangedFileMapper changedFileMapper;
    private final ReviewFindingMapper reviewFindingMapper;
    private final GithubCommentPreviewPublicationLoader previewPublicationLoader;
    private final GithubCommentPreviewItemBuilder previewItemBuilder;
    private final ReviewTaskListItemAssembler listItemAssembler;
    private final ReviewRiskProfileBuilder riskProfileBuilder;
    private final PrReviewSummaryBuilder reviewSummaryBuilder;

    public GithubCommentPublishCandidateLoader(
        ChangedFileMapper changedFileMapper,
        ReviewFindingMapper reviewFindingMapper,
        GithubCommentPreviewPublicationLoader previewPublicationLoader,
        GithubCommentPreviewItemBuilder previewItemBuilder,
        ReviewTaskListItemAssembler listItemAssembler,
        ReviewRiskProfileBuilder riskProfileBuilder,
        PrReviewSummaryBuilder reviewSummaryBuilder
    ) {
        this.changedFileMapper = Objects.requireNonNull(changedFileMapper, "changedFileMapper");
        this.reviewFindingMapper = Objects.requireNonNull(reviewFindingMapper, "reviewFindingMapper");
        this.previewPublicationLoader = Objects.requireNonNull(previewPublicationLoader, "previewPublicationLoader");
        this.previewItemBuilder = Objects.requireNonNull(previewItemBuilder, "previewItemBuilder");
        this.listItemAssembler = Objects.requireNonNull(listItemAssembler, "listItemAssembler");
        this.riskProfileBuilder = Objects.requireNonNull(riskProfileBuilder, "riskProfileBuilder");
        this.reviewSummaryBuilder = Objects.requireNonNull(reviewSummaryBuilder, "reviewSummaryBuilder");
    }

    public GithubCommentPublishCandidateOverview loadOverview(ReviewTask task) {
        Long taskId = task.getId();
        GithubCommentPreviewFindingStat findingStat = reviewFindingMapper.selectGithubCommentPreviewFindingStat(taskId);
        long totalFindings = findingStat == null ? 0L : findingStat.totalFindingsOrZero();
        GithubCommentPublication prSummaryPublication = previewPublicationLoader.loadPrSummaryPublication(taskId);
        GithubCommentPreviewItem prSummaryCandidate = published(prSummaryPublication)
            ? null
            : previewItemBuilder.buildPrSummaryItem(buildPrSummary(task, totalFindings), prSummaryPublication);
        return new GithubCommentPublishCandidateOverview(safeInt(totalFindings), prSummaryCandidate);
    }

    public List<GithubCommentPreviewItem> loadFindingCandidates(
        Long taskId,
        long afterFindingId,
        int limit
    ) {
        if (limit <= 0) {
            return List.of();
        }
        List<ReviewFinding> findings = reviewFindingMapper.selectGithubCommentPublishCandidatesAfterId(
            taskId,
            Math.max(0L, afterFindingId),
            limit
        );
        if (findings == null || findings.isEmpty()) {
            return List.of();
        }
        Map<String, ChangedFile> changedFileByPath = loadChangedFilesForFindings(taskId, findings);
        GithubCommentPreviewPublicationData publicationData = previewPublicationLoader.load(taskId, findings, null);
        return findings.stream()
            .map(finding -> previewItemBuilder.buildFindingItem(
                finding,
                changedFileByPath.get(finding.getFilePath()),
                publicationData.publicationByFindingId().get(finding.getId())
            ))
            .toList();
    }

    private PrReviewSummaryDto buildPrSummary(ReviewTask task, long totalFindings) {
        Long taskId = task.getId();
        ReviewTaskListItem taskItem = listItemAssembler.assemble(task, NO_FAILURE_SUMMARY);
        FindingSeverityCountsDto severityCounts = reviewFindingMapper.selectFindingSeverityCounts(taskId);
        long changedFileTotal = countChangedFiles(taskId);
        long missingTestTotal = countMissingTests(taskId);
        List<ChangedFileDto> focusChangedFiles = loadFocusChangedFiles(taskId);
        PrRiskProfileDto riskProfile = riskProfileBuilder.buildSummary(
            taskItem,
            severityCounts,
            totalFindings,
            changedFileTotal
        );
        return reviewSummaryBuilder.build(
            taskItem,
            List.of(),
            List.of(),
            focusChangedFiles,
            riskProfile,
            severityCounts == null ? FindingSeverityCountsDto.empty() : severityCounts,
            totalFindings,
            missingTestTotal,
            changedFileTotal
        );
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
        List<ChangedFile> files = changedFileMapper.selectTopChangedFilesByChurn(taskId, PR_SUMMARY_FOCUS_FILE_LIMIT);
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        return files.stream()
            .map(file -> new ChangedFileDto(
                file.getFilePath(),
                file.getChangeType(),
                file.getAdditions(),
                file.getDeletions()
            ))
            .toList();
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

    private boolean published(GithubCommentPublication publication) {
        return publication != null
            && Boolean.TRUE.equals(publication.getSuccess())
            && StringUtils.hasText(publication.getGithubUrl());
    }

    private int safeInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, value);
    }

    public record GithubCommentPublishCandidateOverview(
        int totalFindings,
        GithubCommentPreviewItem prSummaryCandidate
    ) {
    }
}
