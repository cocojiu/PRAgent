package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.dto.ChartSliceDto;
import com.repoguard.agent.dto.DashboardHighRiskReview;
import com.repoguard.agent.dto.DashboardLlmQualityModelStat;
import com.repoguard.agent.dto.DashboardLlmQualityTrendCount;
import com.repoguard.agent.dto.DashboardMetricDto;
import com.repoguard.agent.dto.DashboardOverviewResponse;
import com.repoguard.agent.dto.DashboardReviewTrendCount;
import com.repoguard.agent.dto.DashboardRiskLevelCount;
import com.repoguard.agent.dto.DashboardRuleHitCount;
import com.repoguard.agent.dto.FailedRuleStatDto;
import com.repoguard.agent.dto.HighRiskReviewDto;
import com.repoguard.agent.dto.LlmQualityByModelDto;
import com.repoguard.agent.dto.LlmQualityByRepositoryDto;
import com.repoguard.agent.dto.LlmQualityTrendPointDto;
import com.repoguard.agent.dto.ReviewTrendPointDto;
import com.repoguard.agent.dto.SystemHealthItemDto;
import com.repoguard.agent.config.CacheNames;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewPolicyConfigMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.security.SecretCryptoService;
import com.repoguard.agent.service.DashboardService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DashboardServiceImpl implements DashboardService {

    private static final String GITHUB_PROVIDER = "GITHUB";
    private static final DateTimeFormatter TREND_DATE_FORMATTER = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter REVIEWED_AT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewFindingMapper reviewFindingMapper;
    private final IntegrationConfigMapper integrationConfigMapper;
    private final ReviewPolicyConfigMapper reviewPolicyConfigMapper;
    private final RabbitTemplate rabbitTemplate;
    private final SecretCryptoService secretCryptoService;

    public DashboardServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ReviewFindingMapper reviewFindingMapper,
        IntegrationConfigMapper integrationConfigMapper,
        ReviewPolicyConfigMapper reviewPolicyConfigMapper,
        RabbitTemplate rabbitTemplate,
        SecretCryptoService secretCryptoService
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.reviewFindingMapper = reviewFindingMapper;
        this.integrationConfigMapper = integrationConfigMapper;
        this.reviewPolicyConfigMapper = reviewPolicyConfigMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.secretCryptoService = secretCryptoService;
    }

    @Override
    @Cacheable(cacheNames = CacheNames.DASHBOARD_OVERVIEW, key = "#llmTrendDays == null ? 'default' : #llmTrendDays")
    public DashboardOverviewResponse getOverview(Integer llmTrendDays) {
        int normalizedLlmTrendDays = normalizeLlmTrendDays(llmTrendDays);
        List<ReviewTask> tasks = reviewTaskMapper.selectList(
            new LambdaQueryWrapper<ReviewTask>().orderByAsc(ReviewTask::getCreatedAt)
        );
        List<ReviewFinding> findings = reviewFindingMapper.selectList(
            new LambdaQueryWrapper<ReviewFinding>().eq(ReviewFinding::getCategory, "FINDING")
        );
        DashboardMetricStats metricStats = loadMetricStats();
        List<DashboardReviewTrendCount> reviewTrendCounts = reviewTaskMapper.selectReviewTrendCounts();
        List<DashboardHighRiskReview> highRiskReviews = reviewTaskMapper.selectRecentHighRiskReviews();
        List<DashboardLlmQualityModelStat> llmQualityByModelStats = reviewTaskMapper.selectLlmQualityByModelStats();
        List<DashboardLlmQualityTrendCount> llmQualityTrendCounts = reviewTaskMapper.selectLlmQualityTrendCounts(
            LocalDate.now().minusDays(normalizedLlmTrendDays - 1L)
        );
        List<DashboardRuleHitCount> ruleHitCounts = reviewFindingMapper.selectRuleHitCounts();

        return new DashboardOverviewResponse(
            buildMetrics(tasks, metricStats),
            buildTrend(reviewTrendCounts),
            buildRiskDistribution(),
            buildRuleHits(ruleHitCounts),
            buildHighRiskReviews(highRiskReviews),
            buildFailedRules(ruleHitCounts),
            buildSystemHealth(),
            buildLlmQualityByModel(llmQualityByModelStats),
            buildLlmQualityByRepository(tasks, findings),
            buildLlmQualityTrend(llmQualityTrendCounts, normalizedLlmTrendDays)
        );
    }

    private DashboardMetricStats loadMetricStats() {
        long total = safeCount(reviewTaskMapper.selectCount(new LambdaQueryWrapper<>()));
        long highRisk = safeCount(reviewTaskMapper.selectCount(
            new LambdaQueryWrapper<ReviewTask>().in(ReviewTask::getRiskLevel, List.of("HIGH", "CRITICAL"))
        ));
        long failed = safeCount(reviewTaskMapper.selectCount(
            new LambdaQueryWrapper<ReviewTask>().eq(ReviewTask::getStatus, "FAILED")
        ));
        return new DashboardMetricStats(total, highRisk, failed);
    }

    private int normalizeLlmTrendDays(Integer days) {
        if (days == null) {
            return 7;
        }
        return switch (days) {
            case 30 -> 30;
            case 90 -> 90;
            default -> 7;
        };
    }

    private List<SystemHealthItemDto> buildSystemHealth() {
        return List.of(
            new SystemHealthItemDto("MySQL", "正常"),
            new SystemHealthItemDto("RabbitMQ", rabbitMqHealthStatus()),
            new SystemHealthItemDto("GitHub", githubHealthStatus()),
            new SystemHealthItemDto("Spring AI", llmHealthStatus())
        );
    }

    private String rabbitMqHealthStatus() {
        try {
            Boolean open = rabbitTemplate.execute(channel -> channel.isOpen());
            return Boolean.TRUE.equals(open) ? "正常" : "异常";
        } catch (RuntimeException ex) {
            return "异常";
        }
    }

    private String githubHealthStatus() {
        try {
            IntegrationConfig config = integrationConfigMapper.selectOne(
                new LambdaQueryWrapper<IntegrationConfig>().eq(IntegrationConfig::getProvider, GITHUB_PROVIDER)
            );
            if (config == null || !StringUtils.hasText(secretCryptoService.decrypt(config.getTokenValue()))) {
                return "未接入";
            }
            return "FAILED".equalsIgnoreCase(config.getStatus()) ? "异常" : "正常";
        } catch (RuntimeException ex) {
            return "异常";
        }
    }

    private String llmHealthStatus() {
        try {
            ReviewPolicyConfig config = reviewPolicyConfigMapper.selectById(1L);
            if (config == null) {
                return "未接入";
            }
            if (!Boolean.TRUE.equals(config.getLlmEnabled())) {
                return "已禁用";
            }
            boolean configured = StringUtils.hasText(config.getBaseUrl())
                && StringUtils.hasText(config.getModelName())
                && StringUtils.hasText(secretCryptoService.decrypt(config.getApiKeyValue()))
                && !"mock".equalsIgnoreCase(config.getLlmProvider());
            return configured ? "正常" : "未接入";
        } catch (RuntimeException ex) {
            return "异常";
        }
    }

    private List<DashboardMetricDto> buildMetrics(List<ReviewTask> tasks, DashboardMetricStats stats) {
        int avgSeconds = stats.total() == 0
            ? 0
            : (int) Math.round(tasks.stream().mapToInt(task -> nullToZero(task.getDurationSeconds())).average().orElse(0));

        return List.of(
            new DashboardMetricDto("本周审查", String.valueOf(stats.total()), "0.0%", "up", "blue"),
            new DashboardMetricDto("高风险 PR", String.valueOf(stats.highRisk()), percent(stats.highRisk(), stats.total()), "up-danger", "red"),
            new DashboardMetricDto("失败任务", String.valueOf(stats.failed()), percent(stats.failed(), stats.total()), "down", "orange"),
            new DashboardMetricDto("平均审查耗时", formatDuration(avgSeconds), "0.0%", "down", "green")
        );
    }

    private List<ReviewTrendPointDto> buildTrend(List<DashboardReviewTrendCount> reviewTrendCounts) {
        // 当前仪表盘图表直接消费展示标签，因此这里按格式化后的日期聚合。
        return nullToEmpty(reviewTrendCounts).stream()
            .map(count -> new ReviewTrendPointDto(count.getDayLabel(), safeTrendTotal(count)))
            .toList();
    }

    private List<ChartSliceDto> buildRiskDistribution() {
        List<DashboardRiskLevelCount> riskLevelCounts = reviewTaskMapper.selectRiskLevelCounts();
        Map<String, Long> countByRisk = nullToEmpty(riskLevelCounts).stream()
            .collect(Collectors.toMap(DashboardRiskLevelCount::getRiskLevel, this::safeTotal, Long::sum));
        long total = countByRisk.values().stream().mapToLong(Long::longValue).sum();

        return List.of(
            riskSlice("高风险", countByRisk.getOrDefault("HIGH", 0L), total, "#ef4444"),
            riskSlice("中风险", countByRisk.getOrDefault("MEDIUM", 0L), total, "#f59e0b"),
            riskSlice("低风险", countByRisk.getOrDefault("LOW", 0L), total, "#2563eb"),
            riskSlice("提示", countByRisk.getOrDefault("INFO", 0L), total, "#22c55e")
        );
    }

    private List<ChartSliceDto> buildRuleHits(List<DashboardRuleHitCount> ruleHitCounts) {
        long total = totalRuleHits(ruleHitCounts);
        // 没有确定规则编号的问题统一归类为 LLM 审查结果。
        return nullToEmpty(ruleHitCounts).stream()
            .sorted(Comparator.comparingLong(this::safeRuleTotal).reversed())
            .map(count -> new ChartSliceDto(
                ruleName(defaultRuleId(count.getRuleId())),
                safeRuleTotal(count),
                ruleColor(defaultRuleId(count.getRuleId())),
                percent(safeRuleTotal(count), total)
            ))
            .toList();
    }

    private List<HighRiskReviewDto> buildHighRiskReviews(List<DashboardHighRiskReview> highRiskReviews) {
        return nullToEmpty(highRiskReviews).stream()
            .map(review -> new HighRiskReviewDto(
                review.getTitle(),
                review.getRepository(),
                lower(review.getRiskLevel()),
                safeHighRiskRuleHits(review),
                formatReviewedAt(review.getCreatedAt()),
                statusText(review.getStatus())
            ))
            .toList();
    }

    private List<FailedRuleStatDto> buildFailedRules(List<DashboardRuleHitCount> ruleHitCounts) {
        long total = totalRuleHits(ruleHitCounts);
        return nullToEmpty(ruleHitCounts).stream()
            .sorted(Comparator.comparingLong(this::safeRuleTotal).reversed())
            .map(count -> new FailedRuleStatDto(
                ruleName(defaultRuleId(count.getRuleId())),
                safeRuleTotal(count),
                "0.0%",
                "down",
                percent(safeRuleTotal(count), total)
            ))
            .toList();
    }

    private List<LlmQualityByModelDto> buildLlmQualityByModel(List<DashboardLlmQualityModelStat> stats) {
        return nullToEmpty(stats).stream()
            .map(stat -> new LlmQualityByModelDto(
                stat.getModelLabel(),
                safeModelTaskCount(stat),
                formatMilliseconds(toRoundedInt(stat.getAverageDurationMs())),
                formatAverageNumber(stat.getAverageTokens()),
                formatAverageCost(stat.getAverageCost()),
                percentage(safeModelParseSuccessCount(stat), safeModelTaskCount(stat)),
                percentage(safeModelFallbackCount(stat), safeModelTaskCount(stat)),
                percentage(safeModelPartialFallbackCount(stat), safeModelTaskCount(stat)),
                percentage(safeModelValidFeedbackCount(stat), safeModelReviewedFeedbackCount(stat)),
                percentage(safeModelFalsePositiveFeedbackCount(stat), safeModelReviewedFeedbackCount(stat))
            ))
            .toList();
    }

    private List<LlmQualityByRepositoryDto> buildLlmQualityByRepository(List<ReviewTask> tasks, List<ReviewFinding> findings) {
        Map<Long, List<ReviewFinding>> findingsByTask = findingsByTask(findings);
        return llmQualityTasks(tasks).stream()
            .collect(Collectors.groupingBy(this::repositoryLabel))
            .entrySet()
            .stream()
            .sorted(Comparator.<Map.Entry<String, List<ReviewTask>>>comparingInt(entry -> entry.getValue().size()).reversed())
            .limit(6)
            .map(entry -> {
                List<ReviewTask> repositoryTasks = entry.getValue();
                Set<Long> taskIds = taskIds(repositoryTasks);
                List<ReviewFinding> repositoryFindings = findingsForTasks(findingsByTask, taskIds);
                long reviewedCount = reviewedFeedbackCount(repositoryFindings);
                return new LlmQualityByRepositoryDto(
                    entry.getKey(),
                    repositoryTasks.size(),
                    percentage(fallbackCount(repositoryTasks), repositoryTasks.size()),
                    percentage(partialFallbackCount(repositoryTasks), repositoryTasks.size()),
                    percentage(feedbackCount(repositoryFindings, "VALID"), reviewedCount),
                    percentage(feedbackCount(repositoryFindings, "FALSE_POSITIVE"), reviewedCount)
                );
            })
            .toList();
    }

    private List<LlmQualityTrendPointDto> buildLlmQualityTrend(List<DashboardLlmQualityTrendCount> trendCounts, int days) {
        LocalDate today = LocalDate.now();
        Map<String, DashboardLlmQualityTrendCount> countsByDay = nullToEmpty(trendCounts).stream()
            .filter(count -> StringUtils.hasText(count.getDayKey()))
            .collect(Collectors.toMap(DashboardLlmQualityTrendCount::getDayKey, java.util.function.Function.identity(), (first, second) -> first));
        return java.util.stream.IntStream.rangeClosed(0, days - 1)
            .mapToObj(today.minusDays(days - 1L)::plusDays)
            .map(date -> {
                DashboardLlmQualityTrendCount count = countsByDay.get(date.toString());
                long taskCount = safeLlmTaskCount(count);
                return new LlmQualityTrendPointDto(
                    date.format(TREND_DATE_FORMATTER),
                    taskCount,
                    percentage(safeLlmParseSuccessCount(count), taskCount),
                    percentage(safeLlmFallbackCount(count), taskCount),
                    percentage(safeLlmPartialFallbackCount(count), taskCount)
                );
            })
            .toList();
    }

    private List<ReviewTask> llmQualityTasks(List<ReviewTask> tasks) {
        return tasks.stream()
            .filter(task -> StringUtils.hasText(task.getLlmStatus()))
            .filter(task -> !equalsIgnoreCase(task.getLlmStatus(), "PENDING"))
            .toList();
    }

    private String repositoryLabel(ReviewTask task) {
        if (!StringUtils.hasText(task.getOrganization())) {
            return StringUtils.hasText(task.getRepository()) ? task.getRepository().trim() : "unknown";
        }
        return task.getOrganization().trim() + "/" + (StringUtils.hasText(task.getRepository()) ? task.getRepository().trim() : "unknown");
    }

    private Set<Long> taskIds(List<ReviewTask> tasks) {
        return tasks.stream()
            .map(ReviewTask::getId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
    }

    private Map<Long, List<ReviewFinding>> findingsByTask(List<ReviewFinding> findings) {
        return findings.stream()
            .filter(finding -> finding.getTaskId() != null)
            .collect(Collectors.groupingBy(ReviewFinding::getTaskId));
    }

    private List<ReviewFinding> findingsForTasks(Map<Long, List<ReviewFinding>> findingsByTask, Set<Long> taskIds) {
        return taskIds.stream()
            .flatMap(taskId -> findingsByTask.getOrDefault(taskId, List.of()).stream())
            .toList();
    }

    private long fallbackCount(List<ReviewTask> tasks) {
        return tasks.stream()
            .filter(task -> equalsIgnoreCase(task.getLlmStatus(), "FALLBACK") || equalsIgnoreCase(task.getLlmParseStatus(), "FALLBACK"))
            .count();
    }

    private long partialFallbackCount(List<ReviewTask> tasks) {
        return tasks.stream()
            .filter(task -> equalsIgnoreCase(task.getLlmParseStatus(), "PARTIAL_FALLBACK"))
            .count();
    }

    private String formatAverageNumber(BigDecimal average) {
        if (average == null || average.compareTo(BigDecimal.ZERO) <= 0) {
            return "0";
        }
        return String.format(Locale.ROOT, "%.0f", average);
    }

    private String formatAverageCost(BigDecimal average) {
        if (average == null || average.compareTo(BigDecimal.ZERO) <= 0) {
            return "$0.000000";
        }
        return "$" + average.setScale(6, RoundingMode.HALF_UP).toPlainString();
    }

    private int toRoundedInt(BigDecimal value) {
        return value == null ? 0 : value.setScale(0, RoundingMode.HALF_UP).intValue();
    }

    private long reviewedFeedbackCount(List<ReviewFinding> findings) {
        return findings.stream()
            .filter(finding -> StringUtils.hasText(finding.getFeedbackStatus()))
            .filter(finding -> !equalsIgnoreCase(finding.getFeedbackStatus(), "UNREVIEWED"))
            .count();
    }

    private long feedbackCount(List<ReviewFinding> findings, String status) {
        return findings.stream().filter(finding -> equalsIgnoreCase(finding.getFeedbackStatus(), status)).count();
    }

    private String percentage(long value, long total) {
        if (total <= 0) {
            return "0.0%";
        }
        return String.format(Locale.ROOT, "%.1f%%", value * 100.0 / total);
    }

    private String formatMilliseconds(int durationMs) {
        if (durationMs <= 0) {
            return "0 ms";
        }
        if (durationMs < 1000) {
            return durationMs + " ms";
        }
        return String.format(Locale.ROOT, "%.1f s", durationMs / 1000.0);
    }

    private ChartSliceDto riskSlice(String name, long value, long total, String color) {
        return new ChartSliceDto(name, value, color, percent(value, total));
    }

    private String statusText(String status) {
        return switch (status) {
            case "COMPLETED" -> "已完成";
            case "REVIEWING" -> "审查中";
            case "FAILED" -> "失败";
            case "QUEUED" -> "排队中";
            default -> status;
        };
    }

    private String ruleName(String ruleId) {
        return switch (ruleId) {
            case "RG-SECRET-001" -> "硬编码密钥检测";
            case "RG-API-001" -> "Controller 无测试";
            case "RG-DB-001" -> "Entity 无迁移";
            case "RG-CONFIG-001" -> "配置变更风险";
            case "RG-CLEAN-001" -> "TODO/FIXME/System.out";
            case "LLM" -> "LLM 审查";
            default -> ruleId;
        };
    }

    private String ruleColor(String ruleId) {
        return switch (ruleId) {
            case "RG-SECRET-001" -> "#ef4444";
            case "RG-API-001" -> "#f59e0b";
            case "RG-DB-001" -> "#2563eb";
            case "RG-CONFIG-001" -> "#22c55e";
            case "RG-CLEAN-001" -> "#6366f1";
            default -> "#14b8a6";
        };
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private boolean equalsIgnoreCase(String first, String second) {
        return first != null && second != null && first.equalsIgnoreCase(second);
    }

    private String percent(long value, long total) {
        if (total == 0) {
            return "0.0%";
        }
        return String.format(Locale.ROOT, "%.1f%%", value * 100.0 / total);
    }

    private int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    private long safeCount(Long value) {
        return value == null ? 0L : value;
    }

    private long safeTotal(DashboardRiskLevelCount count) {
        return count.getTotal() == null ? 0L : count.getTotal();
    }

    private long safeTrendTotal(DashboardReviewTrendCount count) {
        return count.getTotal() == null ? 0L : count.getTotal();
    }

    private long safeRuleTotal(DashboardRuleHitCount count) {
        return count.getTotal() == null ? 0L : count.getTotal();
    }

    private long safeHighRiskRuleHits(DashboardHighRiskReview review) {
        return review.getRuleHits() == null ? 0L : review.getRuleHits();
    }

    private long safeLlmTaskCount(DashboardLlmQualityTrendCount count) {
        return count == null || count.getTaskCount() == null ? 0L : count.getTaskCount();
    }

    private long safeLlmParseSuccessCount(DashboardLlmQualityTrendCount count) {
        return count == null || count.getParseSuccessCount() == null ? 0L : count.getParseSuccessCount();
    }

    private long safeLlmFallbackCount(DashboardLlmQualityTrendCount count) {
        return count == null || count.getFallbackCount() == null ? 0L : count.getFallbackCount();
    }

    private long safeLlmPartialFallbackCount(DashboardLlmQualityTrendCount count) {
        return count == null || count.getPartialFallbackCount() == null ? 0L : count.getPartialFallbackCount();
    }

    private long safeModelTaskCount(DashboardLlmQualityModelStat stat) {
        return stat.getTaskCount() == null ? 0L : stat.getTaskCount();
    }

    private long safeModelParseSuccessCount(DashboardLlmQualityModelStat stat) {
        return stat.getParseSuccessCount() == null ? 0L : stat.getParseSuccessCount();
    }

    private long safeModelFallbackCount(DashboardLlmQualityModelStat stat) {
        return stat.getFallbackCount() == null ? 0L : stat.getFallbackCount();
    }

    private long safeModelPartialFallbackCount(DashboardLlmQualityModelStat stat) {
        return stat.getPartialFallbackCount() == null ? 0L : stat.getPartialFallbackCount();
    }

    private long safeModelReviewedFeedbackCount(DashboardLlmQualityModelStat stat) {
        return stat.getReviewedFeedbackCount() == null ? 0L : stat.getReviewedFeedbackCount();
    }

    private long safeModelValidFeedbackCount(DashboardLlmQualityModelStat stat) {
        return stat.getValidFeedbackCount() == null ? 0L : stat.getValidFeedbackCount();
    }

    private long safeModelFalsePositiveFeedbackCount(DashboardLlmQualityModelStat stat) {
        return stat.getFalsePositiveFeedbackCount() == null ? 0L : stat.getFalsePositiveFeedbackCount();
    }

    private String formatReviewedAt(LocalDateTime reviewedAt) {
        return reviewedAt == null ? "" : reviewedAt.format(REVIEWED_AT_FORMATTER);
    }

    private long totalRuleHits(List<DashboardRuleHitCount> ruleHitCounts) {
        return nullToEmpty(ruleHitCounts).stream().mapToLong(this::safeRuleTotal).sum();
    }

    private String defaultRuleId(String ruleId) {
        return ruleId == null ? "LLM" : ruleId;
    }

    private <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    private String formatDuration(int durationSeconds) {
        int minutes = durationSeconds / 60;
        int seconds = durationSeconds % 60;
        return minutes + "分 " + seconds + "秒";
    }

    private record DashboardMetricStats(long total, long highRisk, long failed) {
    }
}
