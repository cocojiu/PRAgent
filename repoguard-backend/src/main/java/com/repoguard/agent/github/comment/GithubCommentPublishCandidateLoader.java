package com.repoguard.agent.github.comment;

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
import com.repoguard.agent.review.ReviewFindingProjectionAssembler;
import com.repoguard.agent.review.ReviewRiskProfileBuilder;
import com.repoguard.agent.review.FindingFeedbackStatus;
import com.repoguard.agent.github.comment.GithubCommentPreviewPublicationLoader.GithubCommentPreviewPublicationData;
import com.repoguard.agent.review.task.ReviewFailureSummaryResolver.ReviewFailureSummary;
import com.repoguard.agent.review.task.ReviewTaskListItemAssembler;
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
        GithubCommentPreviewFindingStat findingStat = ReviewFindingProjectionAssembler.toDto(
            reviewFindingMapper.selectGithubCommentPreviewFindingStat(taskId)
        );
        long totalFindings = findingStat == null ? 0L : findingStat.totalFindingsOrZero();
        GithubCommentPublication prSummaryPublication = previewPublicationLoader.loadPrSummaryPublication(taskId);
        List<ReviewFinding> persistingFindings = loadPersistingFindings(taskId);
        GithubCommentPreviewItem prSummaryCandidate = published(prSummaryPublication)
            ? null
            : previewItemBuilder.buildPrSummaryItem(
                buildPrSummary(task, totalFindings, persistingFindings),
                prSummaryPublication
            );
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

    private PrReviewSummaryDto buildPrSummary(
        ReviewTask task,
        long totalFindings,
        List<ReviewFinding> persistingFindings
    ) {
        Long taskId = task.getId();
        ReviewTaskListItem taskItem = listItemAssembler.assemble(task, NO_FAILURE_SUMMARY);
        FindingSeverityCountsDto severityCounts = ReviewFindingProjectionAssembler.toDto(
            reviewFindingMapper.selectFindingSeverityCounts(taskId)
        );
        long changedFileTotal = countChangedFiles(taskId);
        long missingTestTotal = countMissingTests(taskId);
        List<ChangedFileDto> focusChangedFiles = loadFocusChangedFiles(taskId);
        PrRiskProfileDto riskProfile = riskProfileBuilder.buildSummary(
            taskItem,
            severityCounts,
            totalFindings,
            changedFileTotal
        );
        PrReviewSummaryDto summary = reviewSummaryBuilder.build(
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
        return appendPersistingSummary(summary, persistingFindings);
    }

    private List<ReviewFinding> loadPersistingFindings(Long taskId) {
        List<ReviewFinding> findings = reviewFindingMapper.selectList(
            new LambdaQueryWrapper<ReviewFinding>()
                .eq(ReviewFinding::getTaskId, taskId)
                .eq(ReviewFinding::getCurrentAttempt, true)
                .eq(ReviewFinding::getCategory, "FINDING")
                .eq(ReviewFinding::getComparisonStatus, "PERSISTING")
                .orderByAsc(ReviewFinding::getId)
        );
        if (findings == null || findings.isEmpty()) {
            return List.of();
        }
        return findings.stream()
            .filter(finding -> FindingFeedbackStatus.fromFinding(finding).commentable())
            .filter(finding -> !"OBSERVE".equalsIgnoreCase(finding.getEnforcementMode()))
            .toList();
    }

    private PrReviewSummaryDto appendPersistingSummary(
        PrReviewSummaryDto summary,
        List<ReviewFinding> persistingFindings
    ) {
        if (summary == null || persistingFindings == null || persistingFindings.isEmpty()) {
            return summary;
        }
        StringBuilder body = new StringBuilder(summary.githubCommentBody());
        body.append("\n\n**持续问题汇总**\n仍有 ")
            .append(persistingFindings.size())
            .append(" 条问题与上一轮相同，本次不逐条重复评论：");
        persistingFindings.stream().limit(20).forEach(finding -> body
            .append("\n- `")
            .append(location(finding))
            .append("` ")
            .append(summaryText(finding.getMessage())));
        if (persistingFindings.size() > 20) {
            body.append("\n- 其余 ").append(persistingFindings.size() - 20).append(" 条持续问题已折叠");
        }
        return new PrReviewSummaryDto(
            summary.overallRisk(),
            summary.summary(),
            summary.mergeRecommendation(),
            summary.recommendMerge(),
            summary.humanReviewRequired(),
            summary.keyRisks(),
            summary.focusFiles(),
            body.toString()
        );
    }

    private String location(ReviewFinding finding) {
        String path = StringUtils.hasText(finding.getFilePath()) ? finding.getFilePath().trim() : "PR";
        return finding.getLineNumber() == null ? path : path + ":" + finding.getLineNumber();
    }

    private String summaryText(String value) {
        if (!StringUtils.hasText(value)) {
            return "持续问题";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240) + "…";
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
                .eq(ChangedFile::getCurrentAttempt, true)
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
            new LambdaQueryWrapper<ChangedFile>()
                .eq(ChangedFile::getTaskId, taskId)
                .eq(ChangedFile::getCurrentAttempt, true)
        );
        return total == null ? 0L : total;
    }

    private long countMissingTests(Long taskId) {
        Long total = reviewFindingMapper.selectCount(
            new LambdaQueryWrapper<ReviewFinding>()
                .eq(ReviewFinding::getTaskId, taskId)
                .eq(ReviewFinding::getCurrentAttempt, true)
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
