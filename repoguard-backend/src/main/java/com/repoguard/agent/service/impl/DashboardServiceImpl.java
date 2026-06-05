package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.dto.ChartSliceDto;
import com.repoguard.agent.dto.DashboardMetricDto;
import com.repoguard.agent.dto.DashboardOverviewResponse;
import com.repoguard.agent.dto.FailedRuleStatDto;
import com.repoguard.agent.dto.HighRiskReviewDto;
import com.repoguard.agent.dto.ReviewTrendPointDto;
import com.repoguard.agent.dto.SystemHealthItemDto;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.service.DashboardService;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class DashboardServiceImpl implements DashboardService {

    private static final DateTimeFormatter TREND_DATE_FORMATTER = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter REVIEWED_AT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewFindingMapper reviewFindingMapper;

    public DashboardServiceImpl(ReviewTaskMapper reviewTaskMapper, ReviewFindingMapper reviewFindingMapper) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.reviewFindingMapper = reviewFindingMapper;
    }

    @Override
    public DashboardOverviewResponse getOverview() {
        List<ReviewTask> tasks = reviewTaskMapper.selectList(
            new LambdaQueryWrapper<ReviewTask>().orderByAsc(ReviewTask::getCreatedAt)
        );
        List<ReviewFinding> findings = reviewFindingMapper.selectList(
            new LambdaQueryWrapper<ReviewFinding>().eq(ReviewFinding::getCategory, "FINDING")
        );

        return new DashboardOverviewResponse(
            buildMetrics(tasks),
            buildTrend(tasks),
            buildRiskDistribution(tasks),
            buildRuleHits(findings),
            buildHighRiskReviews(tasks, findings),
            buildFailedRules(findings),
            List.of(
                new SystemHealthItemDto("MySQL", "正常"),
                new SystemHealthItemDto("RabbitMQ", "未接入"),
                new SystemHealthItemDto("GitHub", "未接入"),
                new SystemHealthItemDto("Spring AI", "未接入")
            )
        );
    }

    private List<DashboardMetricDto> buildMetrics(List<ReviewTask> tasks) {
        long total = tasks.size();
        long highRisk = tasks.stream().filter(task -> isHighRisk(task.getRiskLevel())).count();
        long failed = tasks.stream().filter(task -> "FAILED".equals(task.getStatus())).count();
        int avgSeconds = total == 0
            ? 0
            : (int) Math.round(tasks.stream().mapToInt(task -> nullToZero(task.getDurationSeconds())).average().orElse(0));

        return List.of(
            new DashboardMetricDto("本周审查", String.valueOf(total), "0.0%", "up", "blue"),
            new DashboardMetricDto("高风险 PR", String.valueOf(highRisk), percent(highRisk, total), "up-danger", "red"),
            new DashboardMetricDto("失败任务", String.valueOf(failed), percent(failed, total), "down", "orange"),
            new DashboardMetricDto("平均审查耗时", formatDuration(avgSeconds), "0.0%", "down", "green")
        );
    }

    private List<ReviewTrendPointDto> buildTrend(List<ReviewTask> tasks) {
        // 当前仪表盘图表直接消费展示标签，因此这里按格式化后的日期聚合。
        Map<String, Long> countByDate = tasks.stream()
            .collect(Collectors.groupingBy(task -> task.getCreatedAt().format(TREND_DATE_FORMATTER), Collectors.counting()));

        return countByDate.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> new ReviewTrendPointDto(entry.getKey(), entry.getValue()))
            .toList();
    }

    private List<ChartSliceDto> buildRiskDistribution(List<ReviewTask> tasks) {
        long total = tasks.size();
        Map<String, Long> countByRisk = tasks.stream()
            .collect(Collectors.groupingBy(ReviewTask::getRiskLevel, Collectors.counting()));

        return List.of(
            riskSlice("高风险", countByRisk.getOrDefault("HIGH", 0L), total, "#ef4444"),
            riskSlice("中风险", countByRisk.getOrDefault("MEDIUM", 0L), total, "#f59e0b"),
            riskSlice("低风险", countByRisk.getOrDefault("LOW", 0L), total, "#2563eb"),
            riskSlice("提示", countByRisk.getOrDefault("INFO", 0L), total, "#22c55e")
        );
    }

    private List<ChartSliceDto> buildRuleHits(List<ReviewFinding> findings) {
        long total = findings.size();
        // 没有确定规则编号的问题统一归类为 LLM 审查结果。
        Map<String, Long> countByRule = findings.stream()
            .map(finding -> finding.getRuleId() == null ? "LLM" : finding.getRuleId())
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        return countByRule.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .map(entry -> new ChartSliceDto(ruleName(entry.getKey()), entry.getValue(), ruleColor(entry.getKey()), percent(entry.getValue(), total)))
            .toList();
    }

    private List<HighRiskReviewDto> buildHighRiskReviews(List<ReviewTask> tasks, List<ReviewFinding> findings) {
        Map<Long, Long> findingCountByTask = findings.stream()
            .collect(Collectors.groupingBy(ReviewFinding::getTaskId, Collectors.counting()));

        return tasks.stream()
            .filter(task -> isHighRisk(task.getRiskLevel()))
            .sorted(Comparator.comparing(ReviewTask::getCreatedAt).reversed())
            .limit(5)
            .map(task -> new HighRiskReviewDto(
                task.getTitle(),
                task.getRepository(),
                lower(task.getRiskLevel()),
                findingCountByTask.getOrDefault(task.getId(), 0L),
                task.getCreatedAt().format(REVIEWED_AT_FORMATTER),
                statusText(task.getStatus())
            ))
            .toList();
    }

    private List<FailedRuleStatDto> buildFailedRules(List<ReviewFinding> findings) {
        long total = findings.size();
        Map<String, Long> countByRule = findings.stream()
            .map(finding -> finding.getRuleId() == null ? "LLM" : finding.getRuleId())
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        return countByRule.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .map(entry -> new FailedRuleStatDto(ruleName(entry.getKey()), entry.getValue(), "0.0%", "down", percent(entry.getValue(), total)))
            .toList();
    }

    private ChartSliceDto riskSlice(String name, long value, long total, String color) {
        return new ChartSliceDto(name, value, color, percent(value, total));
    }

    private boolean isHighRisk(String riskLevel) {
        return "HIGH".equals(riskLevel) || "CRITICAL".equals(riskLevel);
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

    private String percent(long value, long total) {
        if (total == 0) {
            return "0.0%";
        }
        return String.format(Locale.ROOT, "%.1f%%", value * 100.0 / total);
    }

    private int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    private String formatDuration(int durationSeconds) {
        int minutes = durationSeconds / 60;
        int seconds = durationSeconds % 60;
        return minutes + "分 " + seconds + "秒";
    }
}
