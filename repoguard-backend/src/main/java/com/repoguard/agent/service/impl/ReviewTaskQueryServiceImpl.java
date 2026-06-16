package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.ChangedFileDto;
import com.repoguard.agent.dto.ChunkedReviewDto;
import com.repoguard.agent.dto.LlmStatusDto;
import com.repoguard.agent.dto.MissingTestDto;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.PrRiskFileDto;
import com.repoguard.agent.dto.PrRiskProfileDto;
import com.repoguard.agent.dto.PrReviewSummaryDto;
import com.repoguard.agent.dto.RabbitMqStatusDto;
import com.repoguard.agent.dto.ReviewFindingDto;
import com.repoguard.agent.dto.ReviewQuery;
import com.repoguard.agent.dto.ReviewTaskDetail;
import com.repoguard.agent.dto.ReviewTaskListItem;
import com.repoguard.agent.dto.ReviewTaskStatusResponse;
import com.repoguard.agent.dto.ReviewTimelineItem;
import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.service.ReviewTaskQueryService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ReviewTaskQueryServiceImpl implements ReviewTaskQueryService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String SOURCE_MANUAL_INPUT = "MANUAL_INPUT";
    private static final String FEEDBACK_UNREVIEWED = "UNREVIEWED";
    private static final String HUMAN_REVIEW_PENDING = "PENDING";
    private static final String HUMAN_REVIEW_NOT_REQUIRED = "NOT_REQUIRED";
    private static final FailureSummary NO_FAILURE_SUMMARY = new FailureSummary(null, null, null);

    private final ReviewTaskMapper reviewTaskMapper;
    private final ChangedFileMapper changedFileMapper;
    private final ReviewFindingMapper reviewFindingMapper;
    private final ReviewTimelineMapper reviewTimelineMapper;

    public ReviewTaskQueryServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ChangedFileMapper changedFileMapper,
        ReviewFindingMapper reviewFindingMapper,
        ReviewTimelineMapper reviewTimelineMapper
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.changedFileMapper = changedFileMapper;
        this.reviewFindingMapper = reviewFindingMapper;
        this.reviewTimelineMapper = reviewTimelineMapper;
    }

    @Override
    public PageResponse<ReviewTaskListItem> listReviews(ReviewQuery query) {
        Page<ReviewTask> page = reviewTaskMapper.selectPage(
            Page.of(query.page(), query.pageSize()),
            buildListWrapper(query)
        );
        List<ReviewTask> tasks = page.getRecords();
        Map<Long, List<ReviewTimeline>> timelinesByTaskId = loadTimelinesByTaskId(tasks);
        return new PageResponse<>(
            tasks.stream()
                .map(task -> toListItem(task, resolveFailureSummary(task, timelineLabels(timelinesByTaskId.get(task.getId())))))
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

        List<ChangedFileDto> changedFiles = changedFileMapper.selectList(
            new LambdaQueryWrapper<ChangedFile>()
                .eq(ChangedFile::getTaskId, id)
                .orderByAsc(ChangedFile::getId)
        ).stream().map(this::toChangedFileDto).toList();

        List<ReviewFinding> findings = reviewFindingMapper.selectList(
            new LambdaQueryWrapper<ReviewFinding>()
                .eq(ReviewFinding::getTaskId, id)
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
        List<ReviewTimelineItem> timeline = reviewTimelineMapper.selectList(
            new LambdaQueryWrapper<ReviewTimeline>()
                .eq(ReviewTimeline::getTaskId, id)
                .orderByAsc(ReviewTimeline::getSortOrder)
        ).stream().map(this::toTimelineItem).toList();

        return toDetail(task, findingDtos, missingTests, changedFiles, timeline);
    }

    @Override
    public ReviewTaskStatusResponse getReviewStatus(Long id) {
        ReviewTask task = reviewTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + id);
        }

        List<ReviewTimeline> timelines = reviewTimelineMapper.selectList(
            new LambdaQueryWrapper<ReviewTimeline>()
                .eq(ReviewTimeline::getTaskId, id)
                .orderByAsc(ReviewTimeline::getSortOrder)
        );
        ReviewTimelineItem latestTimeline = timelines == null || timelines.isEmpty()
            ? null
            : toTimelineItem(timelines.getLast());
        ReviewTaskListItem item = toListItem(task, resolveFailureSummary(task, timelineLabels(timelines)));

        return new ReviewTaskStatusResponse(
            item.id(),
            item.status(),
            item.riskLevel(),
            item.llmStatus(),
            item.duration(),
            formatDateTimeOrNull(resolveTaskUpdatedAt(task)),
            item.failureCategory(),
            item.failureReason(),
            item.failureSuggestion(),
            latestTimeline,
            item.humanReviewRequired(),
            item.humanReviewStatus(),
            item.humanReviewNote(),
            item.humanReviewBy(),
            item.humanReviewedAt()
        );
    }

    private LambdaQueryWrapper<ReviewTask> buildListWrapper(ReviewQuery query) {
        LambdaQueryWrapper<ReviewTask> wrapper = new LambdaQueryWrapper<ReviewTask>()
            .orderByDesc(ReviewTask::getCreatedAt);

        if (StringUtils.hasText(query.repository())) {
            wrapper.eq(ReviewTask::getRepository, query.repository().trim());
        }
        if (StringUtils.hasText(query.status())) {
            wrapper.eq(ReviewTask::getStatus, query.status().trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(query.riskLevel())) {
            wrapper.eq(ReviewTask::getRiskLevel, query.riskLevel().trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(query.source())) {
            wrapper.eq(ReviewTask::getSource, query.source().trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(query.triggerSource())) {
            wrapper.eq(ReviewTask::getTriggerSource, query.triggerSource().trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(query.keyword())) {
            String keyword = query.keyword().trim();
            Integer prNumber = parseIntegerOrNull(keyword);
            wrapper.and(nested -> nested
                .like(ReviewTask::getTitle, keyword)
                .or()
                .like(ReviewTask::getRepository, keyword)
                .or()
                .like(ReviewTask::getOrganization, keyword)
                .or()
                .like(ReviewTask::getCommitSha, keyword)
                .or(prNumber != null)
                .eq(prNumber != null, ReviewTask::getPrNumber, prNumber)
            );
        }
        return wrapper;
    }

    private Integer parseIntegerOrNull(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private ReviewTaskDetail toDetail(
        ReviewTask task,
        List<ReviewFindingDto> findings,
        List<MissingTestDto> missingTests,
        List<ChangedFileDto> changedFiles,
        List<ReviewTimelineItem> timeline
    ) {
        ReviewTaskListItem item = toListItem(task, resolveFailureSummary(task, timeline.stream().map(ReviewTimelineItem::label).toList()));
        PrRiskProfileDto riskProfile = buildRiskProfile(item, findings, changedFiles);
        String effectiveRiskLevel = effectiveDetailRiskLevel(item.riskLevel(), riskProfile);
        return new ReviewTaskDetail(
            item.id(),
            item.prNumber(),
            item.title(),
            item.repository(),
            item.organization(),
            item.commit(),
            item.branch(),
            item.status(),
            effectiveRiskLevel,
            item.mqRetries(),
            item.llmStatus(),
            item.source(),
            item.triggerSource(),
            item.createdAt(),
            item.duration(),
            item.failureCategory(),
            item.failureReason(),
            item.failureSuggestion(),
            task.getPrUrl(),
            findings,
            missingTests,
            changedFiles,
            timeline,
            riskProfile,
            buildPrReviewSummary(item, findings, missingTests, changedFiles, riskProfile),
            new LlmStatusDto(
                item.llmStatus(),
                item.duration(),
                effectiveRiskLevel,
                lower(task.getLlmProvider()),
                task.getLlmModel(),
                task.getLlmDurationMs(),
                lower(task.getLlmParseStatus()),
                task.getLlmFallbackReason(),
                task.getLlmPromptSummary(),
                task.getLlmPromptTokens(),
                task.getLlmCompletionTokens(),
                task.getLlmTotalTokens(),
                task.getLlmEstimatedCost() == null ? null : task.getLlmEstimatedCost().toPlainString()
            ),
            buildChunkedReview(task.getLlmPromptSummary()),
            new RabbitMqStatusDto(task.getMqRetries() + 1, task.getMqRetries(), "confirmed"),
            item.humanReviewRequired(),
            item.humanReviewStatus(),
            item.humanReviewNote(),
            item.humanReviewBy(),
            item.humanReviewedAt()
        );
    }

    private String effectiveDetailRiskLevel(String taskRiskLevel, PrRiskProfileDto riskProfile) {
        if (riskProfile != null && StringUtils.hasText(riskProfile.level())) {
            return riskProfile.level();
        }
        return lower(taskRiskLevel);
    }

    private ChunkedReviewDto buildChunkedReview(String promptSummary) {
        if (!StringUtils.hasText(promptSummary)) {
            return ChunkedReviewDto.disabled();
        }
        Map<String, String> summary = parsePromptSummary(promptSummary);
        if (!"true".equalsIgnoreCase(summary.get("chunked"))) {
            return ChunkedReviewDto.disabled();
        }
        return new ChunkedReviewDto(
            true,
            parsePositiveInt(summary.get("chunks")),
            lower(summary.get("aggregateRisk")),
            parsePositiveInt(summary.get("aggregateFindings")),
            parsePositiveInt(summary.get("failedChunks")),
            parseChunkReasons(summary.get("chunkReasons"))
        );
    }

    private Map<String, String> parsePromptSummary(String promptSummary) {
        return List.of(promptSummary.split(";")).stream()
            .map(String::trim)
            .filter(part -> part.contains("="))
            .map(part -> part.split("=", 2))
            .filter(parts -> parts.length == 2 && StringUtils.hasText(parts[0]))
            .collect(Collectors.toMap(
                parts -> parts[0].trim(),
                parts -> parts[1].trim(),
                (first, second) -> second
            ));
    }

    private Integer parsePositiveInt(String value) {
        if (!StringUtils.hasText(value)) {
            return 0;
        }
        try {
            return Math.max(Integer.parseInt(value.trim()), 0);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private List<String> parseChunkReasons(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        return List.of(value.split(",")).stream()
            .map(String::trim)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    }

    private PrReviewSummaryDto buildPrReviewSummary(
        ReviewTaskListItem task,
        List<ReviewFindingDto> findings,
        List<MissingTestDto> missingTests,
        List<ChangedFileDto> changedFiles,
        PrRiskProfileDto riskProfile
    ) {
        int criticalCount = countSeverity(findings, "critical");
        int highCount = countSeverity(findings, "high");
        int mediumCount = countSeverity(findings, "medium");
        int totalFindings = findings.size();
        String overallRisk = riskProfile == null ? "info" : riskProfile.level();
        boolean humanReviewRequired = Boolean.TRUE.equals(task.humanReviewRequired())
            || Boolean.TRUE.equals(riskProfile == null ? false : riskProfile.recommendHumanReview());
        boolean recommendMerge = criticalCount == 0 && highCount == 0 && !humanReviewRequired;
        String mergeRecommendation = mergeRecommendation(recommendMerge, criticalCount, highCount, mediumCount, humanReviewRequired);
        List<String> keyRisks = buildPrSummaryRisks(riskProfile, missingTests, criticalCount, highCount, mediumCount);
        List<String> focusFiles = buildPrSummaryFocusFiles(riskProfile, changedFiles);
        String summary = "本次 PR 综合风险为 " + riskText(overallRisk)
            + "，包含 " + changedFiles.size() + " 个变更文件、" + totalFindings + " 条审查发现"
            + (missingTests.isEmpty() ? "" : "、" + missingTests.size() + " 条缺失测试建议")
            + "。";
        String commentBody = buildPrSummaryCommentBody(task, overallRisk, summary, mergeRecommendation, keyRisks, focusFiles);
        return new PrReviewSummaryDto(
            overallRisk,
            summary,
            mergeRecommendation,
            recommendMerge,
            humanReviewRequired,
            keyRisks,
            focusFiles,
            commentBody
        );
    }

    private String mergeRecommendation(
        boolean recommendMerge,
        int criticalCount,
        int highCount,
        int mediumCount,
        boolean humanReviewRequired
    ) {
        if (criticalCount > 0 || highCount > 0) {
            return "暂不建议直接合并，请优先处理高风险发现后再评估。";
        }
        if (humanReviewRequired || mediumCount > 0) {
            return "建议完成必要人工复核和中风险确认后再合并。";
        }
        return recommendMerge ? "未发现阻塞性风险，可按团队流程合并。" : "建议完成复核后再合并。";
    }

    private List<String> buildPrSummaryRisks(
        PrRiskProfileDto riskProfile,
        List<MissingTestDto> missingTests,
        int criticalCount,
        int highCount,
        int mediumCount
    ) {
        List<String> risks = new java.util.ArrayList<>();
        if (criticalCount > 0) {
            risks.add("包含 " + criticalCount + " 条严重风险发现");
        }
        if (highCount > 0) {
            risks.add("包含 " + highCount + " 条高风险发现");
        }
        if (mediumCount > 0) {
            risks.add("包含 " + mediumCount + " 条中风险发现");
        }
        if (!missingTests.isEmpty()) {
            risks.add("存在 " + missingTests.size() + " 条缺失测试建议");
        }
        if (riskProfile != null && riskProfile.signals() != null) {
            riskProfile.signals().stream()
                .filter(StringUtils::hasText)
                .filter(signal -> risks.stream().noneMatch(existing -> existing.equals(signal)))
                .limit(Math.max(0, 5 - risks.size()))
                .forEach(risks::add);
        }
        if (risks.isEmpty()) {
            risks.add("未发现明显阻塞性风险");
        }
        return risks.stream().limit(5).toList();
    }

    private List<String> buildPrSummaryFocusFiles(PrRiskProfileDto riskProfile, List<ChangedFileDto> changedFiles) {
        List<String> files = new java.util.ArrayList<>();
        if (riskProfile != null && riskProfile.highRiskFiles() != null) {
            riskProfile.highRiskFiles().stream()
                .map(PrRiskFileDto::file)
                .filter(StringUtils::hasText)
                .limit(3)
                .forEach(files::add);
        }
        if (files.size() < 3) {
            changedFiles.stream()
                .sorted(Comparator.comparingInt(file -> -(safeInt(file.additions()) + safeInt(file.deletions()))))
                .map(ChangedFileDto::path)
                .filter(StringUtils::hasText)
                .filter(file -> !files.contains(file))
                .limit(3 - files.size())
                .forEach(files::add);
        }
        return files;
    }

    private String buildPrSummaryCommentBody(
        ReviewTaskListItem task,
        String overallRisk,
        String summary,
        String mergeRecommendation,
        List<String> keyRisks,
        List<String> focusFiles
    ) {
        StringBuilder body = new StringBuilder();
        body.append("## RepoGuard PR 总评");
        body.append("\n\n").append(summary);
        body.append("\n\n**合并建议**：").append(mergeRecommendation);
        body.append("\n\n**关键风险**");
        keyRisks.forEach(risk -> body.append("\n- ").append(risk));
        if (!focusFiles.isEmpty()) {
            body.append("\n\n**建议重点查看文件**");
            focusFiles.forEach(file -> body.append("\n- `").append(file).append("`"));
        }
        body.append("\n\n> 任务 #").append(task.id()).append("，风险等级：").append(riskText(overallRisk)).append("。");
        return body.toString();
    }

    private String riskText(String riskLevel) {
        if (!StringUtils.hasText(riskLevel)) {
            return "提示";
        }
        return switch (riskLevel.trim().toLowerCase(Locale.ROOT)) {
            case "critical" -> "严重";
            case "high" -> "高";
            case "medium" -> "中";
            case "low" -> "低";
            default -> "提示";
        };
    }

    private PrRiskProfileDto buildRiskProfile(
        ReviewTaskListItem task,
        List<ReviewFindingDto> findings,
        List<ChangedFileDto> changedFiles
    ) {
        Map<String, Long> findingCountByFile = findings.stream()
            .filter(finding -> StringUtils.hasText(finding.file()))
            .collect(Collectors.groupingBy(ReviewFindingDto::file, Collectors.counting()));
        int criticalCount = countSeverity(findings, "critical");
        int highCount = countSeverity(findings, "high");
        int mediumCount = countSeverity(findings, "medium");
        int lowCount = countSeverity(findings, "low");
        int totalChurn = changedFiles.stream()
            .mapToInt(file -> safeInt(file.additions()) + safeInt(file.deletions()))
            .sum();
        int sensitiveFileCount = (int) changedFiles.stream().filter(file -> !riskReasons(file).isEmpty()).count();

        int score = criticalCount * 35
            + highCount * 25
            + mediumCount * 12
            + lowCount * 4
            + Math.min(changedFiles.size() * 2, 20)
            + Math.min(totalChurn / 50, 20)
            + Math.min(sensitiveFileCount * 8, 24);
        score = Math.min(score, 100);
        String level = scoreToRiskLevel(score, task.riskLevel());

        List<String> signals = new java.util.ArrayList<>();
        if (criticalCount + highCount > 0) {
            signals.add("包含 " + (criticalCount + highCount) + " 条高危以上发现");
        }
        if (mediumCount > 0) {
            signals.add("包含 " + mediumCount + " 条中风险发现");
        }
        if (changedFiles.size() >= 8) {
            signals.add("变更文件较多：" + changedFiles.size() + " 个文件");
        }
        if (totalChurn >= 300) {
            signals.add("变更规模较大：" + totalChurn + " 行增删");
        }
        if (sensitiveFileCount > 0) {
            signals.add("触及 " + sensitiveFileCount + " 个敏感文件");
        }
        if (signals.isEmpty()) {
            signals.add("未发现明显放大风险的变更信号");
        }

        boolean recommendHumanReview = score >= 55 || Boolean.TRUE.equals(task.humanReviewRequired());
        String humanReviewReason = recommendHumanReview
            ? "风险分达到 " + score + "，建议人工复核后再回写或合并。"
            : "风险分较低，可按常规自动审查流程推进。";
        List<PrRiskFileDto> highRiskFiles = changedFiles.stream()
            .map(file -> toRiskFile(file, findingCountByFile.getOrDefault(file.path(), 0L).intValue()))
            .filter(file -> file.score() > 0)
            .sorted(Comparator.comparing(PrRiskFileDto::score).reversed())
            .limit(5)
            .toList();

        return new PrRiskProfileDto(
            score,
            level,
            buildRiskSummary(level, score, findings.size(), changedFiles.size(), totalChurn),
            recommendHumanReview,
            humanReviewReason,
            signals,
            highRiskFiles
        );
    }

    private PrRiskFileDto toRiskFile(ChangedFileDto file, int findingCount) {
        List<String> reasons = riskReasons(file);
        int churn = safeInt(file.additions()) + safeInt(file.deletions());
        int score = findingCount * 18 + Math.min(churn / 25, 20) + reasons.size() * 12;
        return new PrRiskFileDto(
            file.path(),
            file.changeType(),
            file.additions(),
            file.deletions(),
            findingCount,
            Math.min(score, 100),
            reasons
        );
    }

    private List<String> riskReasons(ChangedFileDto file) {
        String path = file.path() == null ? "" : file.path().toLowerCase(Locale.ROOT);
        List<String> reasons = new java.util.ArrayList<>();
        if (path.contains("db/migration") || path.endsWith(".sql")) {
            reasons.add("数据库迁移");
        }
        if (path.contains("security") || path.contains("auth") || path.contains("token") || path.contains("permission")) {
            reasons.add("认证或权限");
        }
        if (path.endsWith("application.yml") || path.endsWith("application-prod.yml") || path.contains("config")) {
            reasons.add("运行配置");
        }
        if (path.contains(".github/") || path.contains("docker") || path.endsWith("pom.xml") || path.endsWith("package.json")) {
            reasons.add("构建或发布链路");
        }
        return reasons;
    }

    private int countSeverity(List<ReviewFindingDto> findings, String severity) {
        return (int) findings.stream().filter(finding -> severity.equalsIgnoreCase(finding.severity())).count();
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private String scoreToRiskLevel(int score, String fallbackRiskLevel) {
        if (score >= 80 || "critical".equalsIgnoreCase(fallbackRiskLevel)) {
            return "critical";
        }
        if (score >= 55 || "high".equalsIgnoreCase(fallbackRiskLevel)) {
            return "high";
        }
        if (score >= 30 || "medium".equalsIgnoreCase(fallbackRiskLevel)) {
            return "medium";
        }
        if (score >= 10 || "low".equalsIgnoreCase(fallbackRiskLevel)) {
            return "low";
        }
        return "info";
    }

    private String buildRiskSummary(String level, int score, int findingCount, int fileCount, int totalChurn) {
        return "本次 PR 综合风险为 " + lower(level)
            + "（" + score + "/100），覆盖 "
            + fileCount + " 个变更文件、" + totalChurn + " 行增删，审查发现 "
            + findingCount + " 条。";
    }

    private ReviewTaskListItem toListItem(ReviewTask task, FailureSummary failureSummary) {
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
            failureSummary.category(),
            failureSummary.reason(),
            failureSummary.suggestion(),
            Boolean.TRUE.equals(task.getHumanReviewRequired()),
            lower(resolveHumanReviewStatus(task)),
            task.getHumanReviewNote(),
            task.getHumanReviewBy(),
            formatDateTimeOrNull(task.getHumanReviewedAt())
        );
    }

    private Map<Long, List<ReviewTimeline>> loadTimelinesByTaskId(List<ReviewTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> taskIds = tasks.stream()
            .map(ReviewTask::getId)
            .filter(id -> id != null)
            .toList();
        if (taskIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<ReviewTimeline> timelines = reviewTimelineMapper.selectList(
            new LambdaQueryWrapper<ReviewTimeline>()
                .in(ReviewTimeline::getTaskId, taskIds)
                .orderByAsc(ReviewTimeline::getTaskId)
                .orderByAsc(ReviewTimeline::getSortOrder)
        );
        if (timelines == null || timelines.isEmpty()) {
            return Collections.emptyMap();
        }
        return timelines.stream().collect(Collectors.groupingBy(ReviewTimeline::getTaskId));
    }

    private List<String> timelineLabels(List<ReviewTimeline> timelines) {
        if (timelines == null || timelines.isEmpty()) {
            return List.of();
        }
        return timelines.stream().map(ReviewTimeline::getLabel).toList();
    }

    private FailureSummary resolveFailureSummary(ReviewTask task, List<String> timelineLabels) {
        if (!"FAILED".equals(task.getStatus())) {
            return NO_FAILURE_SUMMARY;
        }

        String detail = timelineLabels.stream()
            .filter(StringUtils::hasText)
            .filter(label -> label.equals("Review failed") || label.startsWith("Review failed:"))
            .reduce((first, second) -> second)
            .map(this::extractFailureDetail)
            .orElse("");
        return classifyFailure(detail);
    }

    private String extractFailureDetail(String label) {
        if (label.startsWith("Review failed:")) {
            return label.replaceFirst("Review failed:", "").trim();
        }
        return "";
    }

    private FailureSummary classifyFailure(String detail) {
        String normalized = StringUtils.hasText(detail) ? detail.trim() : "";
        String lowerDetail = normalized.toLowerCase(Locale.ROOT);

        if (lowerDetail.contains("category=github_token_invalid")) {
            return new FailureSummary(
                "github_token_invalid",
                "GitHub Token 无效或已过期",
                "请到集成配置页更新 GitHub Token，确认保存成功后再重试审查。"
            );
        }
        if (lowerDetail.contains("category=github_permission_denied")) {
            return new FailureSummary(
                "github_permission_denied",
                "GitHub Token 权限不足",
                "请确认 Token 对目标仓库和 PR 具备读取权限，必要时补充 repo 权限后重试。"
            );
        }
        if (lowerDetail.contains("category=github_target_not_found")) {
            return new FailureSummary(
                "github_target_not_found",
                "PR 或仓库不存在/不可访问",
                "请确认仓库名称、组织、PR 编号和 Token 可访问范围，然后重新触发审查。"
            );
        }
        if (lowerDetail.contains("category=github_rate_limited")) {
            return new FailureSummary(
                "github_rate_limited",
                "GitHub API 访问受限",
                "请稍后重试，或更换剩余额度充足的 GitHub Token。"
            );
        }
        if (lowerDetail.contains("category=github_service_unavailable")) {
            return new FailureSummary(
                "github_service_unavailable",
                "GitHub API 暂时不可用",
                "请稍后重试，并关注 GitHub 服务状态或企业代理网络状态。"
            );
        }
        if (lowerDetail.contains("category=github_timeout")) {
            return new FailureSummary(
                "github_timeout",
                "GitHub API 响应超时",
                "请检查网络、GitHub 服务状态和代理配置，稍后再重试审查。"
            );
        }
        if (lowerDetail.contains("category=llm_auth_failed")) {
            return new FailureSummary(
                "llm_auth_failed",
                "LLM 鉴权失败",
                "请检查 LLM API Key、Provider 和 Base URL 配置，保存成功后再重试。"
            );
        }
        if (lowerDetail.contains("category=llm_rate_limited")) {
            return new FailureSummary(
                "llm_rate_limited",
                "LLM 调用受限",
                "请稍后重试，或调整供应商额度、并发与限流配置。"
            );
        }
        if (lowerDetail.contains("category=llm_service_unavailable")) {
            return new FailureSummary(
                "llm_service_unavailable",
                "LLM 服务暂时不可用",
                "请稍后重试，必要时切换模型或启用规则兜底。"
            );
        }
        if (lowerDetail.contains("category=llm_timeout")) {
            return new FailureSummary(
                "llm_timeout",
                "LLM 响应超时",
                "请检查模型服务状态、网络和超时配置，稍后再重试。"
            );
        }
        if (lowerDetail.contains("category=llm_request_invalid")
            || lowerDetail.contains("category=llm_model_or_endpoint_not_found")) {
            return new FailureSummary(
                "llm_request_invalid",
                "LLM 请求配置无效",
                "请检查模型名称、Base URL、请求参数和供应商兼容性配置。"
            );
        }
        if (lowerDetail.contains("401")
            || lowerDetail.contains("bad credentials")
            || lowerDetail.contains("unauthorized")
            || lowerDetail.contains("requires authentication")) {
            return new FailureSummary(
                "github_token_invalid",
                "GitHub Token 无效或已过期",
                "请到集成配置页更新 GitHub Token，确认保存成功后再重试审查。"
            );
        }
        if (lowerDetail.contains("403")
            || lowerDetail.contains("forbidden")
            || lowerDetail.contains("resource not accessible")
            || lowerDetail.contains("permission")) {
            return new FailureSummary(
                "github_permission_denied",
                "GitHub Token 权限不足",
                "请确认 Token 对目标仓库和 PR 具备读取权限，必要时补充 repo 权限后重试。"
            );
        }
        if (lowerDetail.contains("404") || lowerDetail.contains("not found")) {
            return new FailureSummary(
                "github_resource_not_found",
                "PR 或仓库不存在/不可访问",
                "请确认仓库名称、组织、PR 编号和 Token 可访问范围，然后重新触发审查。"
            );
        }
        if (lowerDetail.contains("rate limit")) {
            return new FailureSummary(
                "github_rate_limited",
                "GitHub API 访问受限",
                "请稍后重试，或更换剩余额度充足的 GitHub Token。"
            );
        }
        if (lowerDetail.contains("timeout") || lowerDetail.contains("timed out")) {
            return new FailureSummary(
                "external_service_timeout",
                "外部服务响应超时",
                "请检查网络、GitHub 和 LLM 服务状态，稍后再重试审查。"
            );
        }
        if (lowerDetail.contains("unable to parse llm review result") || lowerDetail.contains("llm review result")) {
            return new FailureSummary(
                "llm_result_parse_failed",
                "LLM 输出解析失败",
                "请检查 LLM 模型返回格式或临时启用规则兜底后重试。"
            );
        }
        if (lowerDetail.contains("llm config is incomplete") || lowerDetail.contains("api key")) {
            return new FailureSummary(
                "llm_config_incomplete",
                "LLM 配置不完整",
                "请在系统配置中补全 LLM Provider、模型和密钥，保存后再重试。"
            );
        }
        return new FailureSummary(
            "review_execution_failed",
            "审查执行失败",
            "请检查 GitHub/LLM 集成配置和任务时间线，修复后点击重试。"
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

    private ReviewTimelineItem toTimelineItem(ReviewTimeline timeline) {
        return new ReviewTimelineItem(
            timeline.getLabel(),
            timeline.getEventTime().format(TIME_FORMATTER),
            switch (timeline.getStatus()) {
                case "DONE" -> "done";
                case "CURRENT" -> "current";
                case "FAILED" -> "done";
                default -> "pending";
            }
        );
    }

    private String resolveStoredSource(String source) {
        return StringUtils.hasText(source) ? source : SOURCE_MANUAL_INPUT;
    }

    private String resolveFindingFeedbackStatus(ReviewFinding finding) {
        return StringUtils.hasText(finding.getFeedbackStatus()) ? finding.getFeedbackStatus() : FEEDBACK_UNREVIEWED;
    }

    private String resolveHumanReviewStatus(ReviewTask task) {
        if (!Boolean.TRUE.equals(task.getHumanReviewRequired())) {
            return HUMAN_REVIEW_NOT_REQUIRED;
        }
        return StringUtils.hasText(task.getHumanReviewStatus()) ? task.getHumanReviewStatus() : HUMAN_REVIEW_PENDING;
    }

    private LocalDateTime resolveTaskUpdatedAt(ReviewTask task) {
        if (task.getFinishedAt() != null) {
            return task.getFinishedAt();
        }
        if (task.getStartedAt() != null) {
            return task.getStartedAt();
        }
        return task.getCreatedAt();
    }

    private String formatDateTimeOrNull(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME_FORMATTER);
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private String formatDuration(Integer durationSeconds) {
        int totalSeconds = durationSeconds == null ? 0 : durationSeconds;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return minutes + " 分 " + seconds + " 秒";
    }

    private record FailureSummary(String category, String reason, String suggestion) {
    }
}
