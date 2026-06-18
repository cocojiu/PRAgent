package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.GithubIntegrationProvider;
import com.repoguard.agent.dto.ChangedFileDto;
import com.repoguard.agent.dto.GithubCommentPreviewItem;
import com.repoguard.agent.dto.GithubCommentPreviewResponse;
import com.repoguard.agent.dto.MissingTestDto;
import com.repoguard.agent.dto.PrRiskProfileDto;
import com.repoguard.agent.dto.PrReviewSummaryDto;
import com.repoguard.agent.dto.ReviewFindingDto;
import com.repoguard.agent.dto.ReviewTaskListItem;
import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.GithubCommentPublication;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.HumanReviewStatus;
import com.repoguard.agent.review.PrReviewSummaryBuilder;
import com.repoguard.agent.review.ReviewRiskProfileBuilder;
import com.repoguard.agent.service.GithubCommentPreviewService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class GithubCommentPreviewServiceImpl implements GithubCommentPreviewService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String SOURCE_MANUAL_INPUT = "MANUAL_INPUT";
    private static final String FEEDBACK_UNREVIEWED = "UNREVIEWED";

    private final ReviewTaskMapper reviewTaskMapper;
    private final ChangedFileMapper changedFileMapper;
    private final ReviewFindingMapper reviewFindingMapper;
    private final GithubCommentPublicationMapper githubCommentPublicationMapper;
    private final GithubIntegrationProvider githubIntegrationProvider;
    private final ReviewRiskProfileBuilder riskProfileBuilder;
    private final PrReviewSummaryBuilder reviewSummaryBuilder;
    private final GithubCommentWritebackCheckBuilder writebackCheckBuilder;
    private final GithubCommentPreviewItemBuilder previewItemBuilder;

    public GithubCommentPreviewServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ChangedFileMapper changedFileMapper,
        ReviewFindingMapper reviewFindingMapper,
        GithubCommentPublicationMapper githubCommentPublicationMapper,
        GithubIntegrationProvider githubIntegrationProvider,
        ReviewRiskProfileBuilder riskProfileBuilder,
        PrReviewSummaryBuilder reviewSummaryBuilder
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.changedFileMapper = changedFileMapper;
        this.reviewFindingMapper = reviewFindingMapper;
        this.githubCommentPublicationMapper = githubCommentPublicationMapper;
        this.githubIntegrationProvider = githubIntegrationProvider;
        this.riskProfileBuilder = riskProfileBuilder;
        this.reviewSummaryBuilder = reviewSummaryBuilder;
        this.writebackCheckBuilder = new GithubCommentWritebackCheckBuilder();
        this.previewItemBuilder = new GithubCommentPreviewItemBuilder();
    }

    @Override
    public GithubCommentPreviewResponse getPreview(Long taskId) {
        ReviewTask task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + taskId);
        }

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
        ReviewTaskListItem taskItem = toListItem(task);
        PrRiskProfileDto riskProfile = riskProfileBuilder.build(taskItem, findingDtos, changedFileDtos);
        PrReviewSummaryDto prSummary = reviewSummaryBuilder.build(
            taskItem,
            findingDtos,
            missingTests,
            changedFileDtos,
            riskProfile
        );
        Map<Long, GithubCommentPublication> publicationByFindingId = loadPublicationByFindingId(
            taskId,
            actionableFindings
        );
        GithubCommentPublication prSummaryPublication = loadPrSummaryPublication(taskId);

        List<GithubCommentPreviewItem> items = new java.util.ArrayList<>();
        items.add(previewItemBuilder.buildPrSummaryItem(prSummary, prSummaryPublication));
        items.addAll(actionableFindings.stream()
            .map(finding -> previewItemBuilder.buildFindingItem(
                finding,
                changedFileByPath.get(finding.getFilePath()),
                publicationByFindingId.get(finding.getId())
            ))
            .toList());

        int commentableCount = (int) items.stream().filter(GithubCommentPreviewItem::commentable).count();
        int publishedCount = (int) items.stream().filter(item -> Boolean.TRUE.equals(item.published())).count();
        return new GithubCommentPreviewResponse(
            task.getId(),
            task.getPrNumber(),
            task.getPrUrl(),
            writebackCheckBuilder.build(task, githubIntegrationProvider.getSettings()),
            actionableFindings.size(),
            commentableCount,
            items.size() - commentableCount - publishedCount,
            items
        );
    }

    private Map<Long, GithubCommentPublication> loadPublicationByFindingId(
        Long taskId,
        List<ReviewFinding> findings
    ) {
        if (findings == null || findings.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> findingIds = findings.stream().map(ReviewFinding::getId).toList();
        List<GithubCommentPublication> publications = githubCommentPublicationMapper.selectList(
            new LambdaQueryWrapper<GithubCommentPublication>()
                .eq(GithubCommentPublication::getTaskId, taskId)
                .in(GithubCommentPublication::getFindingId, findingIds)
        );
        if (publications == null || publications.isEmpty()) {
            return Collections.emptyMap();
        }
        return publications.stream().collect(Collectors.toMap(
            GithubCommentPublication::getFindingId,
            Function.identity(),
            (first, ignored) -> first
        ));
    }

    private GithubCommentPublication loadPrSummaryPublication(Long taskId) {
        return githubCommentPublicationMapper.selectOne(
            new LambdaQueryWrapper<GithubCommentPublication>()
                .eq(GithubCommentPublication::getTaskId, taskId)
                .isNull(GithubCommentPublication::getFindingId)
                .eq(GithubCommentPublication::getTargetType, "pull_request")
                .last("limit 1")
        );
    }

    private ReviewTaskListItem toListItem(ReviewTask task) {
        return new ReviewTaskListItem(
            task.getId(),
            task.getPrNumber(),
            task.getTitle(),
            task.getRepository(),
            task.getOrganization(),
            task.getCommitSha(),
            task.getBranchName(),
            lower(task.getStatus()),
            lower(task.getRiskLevel()),
            task.getMqRetries(),
            lower(task.getLlmStatus()),
            lower(resolveStoredSource(task.getSource())),
            lower(resolveStoredSource(task.getTriggerSource())),
            task.getCreatedAt().format(DATE_TIME_FORMATTER),
            formatDuration(task.getDurationSeconds()),
            null,
            null,
            null,
            Boolean.TRUE.equals(task.getHumanReviewRequired()),
            lower(resolveHumanReviewStatus(task)),
            task.getHumanReviewNote(),
            task.getHumanReviewBy(),
            formatDateTimeOrNull(task.getHumanReviewedAt())
        );
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

    private String resolveStoredSource(String source) {
        return StringUtils.hasText(source) ? source : SOURCE_MANUAL_INPUT;
    }

    private String resolveFindingFeedbackStatus(ReviewFinding finding) {
        return StringUtils.hasText(finding.getFeedbackStatus()) ? finding.getFeedbackStatus() : FEEDBACK_UNREVIEWED;
    }

    private String resolveHumanReviewStatus(ReviewTask task) {
        if (StringUtils.hasText(task.getHumanReviewStatus())) {
            return HumanReviewStatus.from(task.getHumanReviewStatus()).code();
        }
        return HumanReviewStatus.defaultForRequired(Boolean.TRUE.equals(task.getHumanReviewRequired())).code();
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
}
