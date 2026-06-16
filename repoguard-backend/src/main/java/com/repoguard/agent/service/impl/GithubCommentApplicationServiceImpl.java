package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.GithubCommentPublicationBatchDto;
import com.repoguard.agent.dto.GithubCommentPublicationHistoryItem;
import com.repoguard.agent.dto.GithubCommentPublicationHistoryResponse;
import com.repoguard.agent.entity.GithubCommentPublicationBatch;
import com.repoguard.agent.entity.GithubCommentPublicationBatchItem;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchItemMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.service.GithubCommentApplicationService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class GithubCommentApplicationServiceImpl implements GithubCommentApplicationService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final FailureSummary NO_FAILURE_SUMMARY = new FailureSummary(null, null, null);

    private final ReviewTaskMapper reviewTaskMapper;
    private final GithubCommentPublicationBatchMapper githubCommentPublicationBatchMapper;
    private final GithubCommentPublicationBatchItemMapper githubCommentPublicationBatchItemMapper;

    public GithubCommentApplicationServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        GithubCommentPublicationBatchMapper githubCommentPublicationBatchMapper,
        GithubCommentPublicationBatchItemMapper githubCommentPublicationBatchItemMapper
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.githubCommentPublicationBatchMapper = githubCommentPublicationBatchMapper;
        this.githubCommentPublicationBatchItemMapper = githubCommentPublicationBatchItemMapper;
    }

    @Override
    public GithubCommentPublicationHistoryResponse getGithubCommentPublicationHistory(
        Long taskId,
        int page,
        int pageSize,
        String status
    ) {
        ReviewTask task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + taskId);
        }

        String normalizedStatus = normalizeOptionalStatus(status);
        LambdaQueryWrapper<GithubCommentPublicationBatch> batchQuery = new LambdaQueryWrapper<GithubCommentPublicationBatch>()
            .eq(GithubCommentPublicationBatch::getTaskId, taskId)
            .eq(normalizedStatus != null, GithubCommentPublicationBatch::getStatus, normalizedStatus)
            .orderByDesc(GithubCommentPublicationBatch::getCreatedAt)
            .orderByDesc(GithubCommentPublicationBatch::getId);
        Page<GithubCommentPublicationBatch> batchPage = githubCommentPublicationBatchMapper.selectPage(
            Page.of(page, pageSize),
            batchQuery
        );
        List<GithubCommentPublicationBatch> batches = batchPage.getRecords();
        if (batches == null || batches.isEmpty()) {
            return new GithubCommentPublicationHistoryResponse(
                task.getId(),
                batchPage.getTotal(),
                page,
                pageSize,
                normalizedStatus,
                List.of()
            );
        }

        List<Long> batchIds = batches.stream().map(GithubCommentPublicationBatch::getId).toList();
        Map<Long, List<GithubCommentPublicationBatchItem>> itemsByBatchId = githubCommentPublicationBatchItemMapper.selectList(
            new LambdaQueryWrapper<GithubCommentPublicationBatchItem>()
                .in(GithubCommentPublicationBatchItem::getBatchId, batchIds)
                .orderByAsc(GithubCommentPublicationBatchItem::getId)
        ).stream().collect(Collectors.groupingBy(GithubCommentPublicationBatchItem::getBatchId));

        List<GithubCommentPublicationBatchDto> batchDtos = batches.stream()
            .map(batch -> toGithubCommentPublicationBatchDto(batch, itemsByBatchId.getOrDefault(batch.getId(), List.of())))
            .toList();
        return new GithubCommentPublicationHistoryResponse(
            task.getId(),
            batchPage.getTotal(),
            page,
            pageSize,
            normalizedStatus,
            batchDtos
        );
    }

    private String normalizeOptionalStatus(String status) {
        return StringUtils.hasText(status) ? status.trim().toLowerCase(Locale.ROOT) : null;
    }

    private GithubCommentPublicationBatchDto toGithubCommentPublicationBatchDto(
        GithubCommentPublicationBatch batch,
        List<GithubCommentPublicationBatchItem> items
    ) {
        return new GithubCommentPublicationBatchDto(
            batch.getId(),
            batch.getStatus(),
            batch.getTotalFindings(),
            batch.getAttemptedCount(),
            batch.getSucceededCount(),
            batch.getFailedCount(),
            batch.getSkippedCount(),
            formatDateTimeOrNull(batch.getCreatedAt()),
            formatDateTimeOrNull(batch.getCompletedAt()),
            items.stream().map(this::toGithubCommentPublicationHistoryItem).toList()
        );
    }

    private GithubCommentPublicationHistoryItem toGithubCommentPublicationHistoryItem(GithubCommentPublicationBatchItem item) {
        FailureSummary failureSummary = resolveGithubWritebackFailure(item.getStatus(), item.getSuccess(), item.getMessage());
        return new GithubCommentPublicationHistoryItem(
            item.getFindingId(),
            item.getFilePath(),
            item.getLineNumber(),
            item.getTargetType(),
            item.getSuccess(),
            item.getStatus(),
            item.getMessage(),
            failureSummary.category(),
            failureSummary.reason(),
            failureSummary.suggestion(),
            item.getGithubUrl(),
            item.getGithubCommentId(),
            formatDateTimeOrNull(item.getPublishedAt())
        );
    }

    private FailureSummary resolveGithubWritebackFailure(String status, Boolean success, String message) {
        if (Boolean.TRUE.equals(success) || !"failed".equalsIgnoreCase(status)) {
            return NO_FAILURE_SUMMARY;
        }
        return classifyGithubWritebackFailure(message);
    }

    private FailureSummary classifyGithubWritebackFailure(String message) {
        String normalized = StringUtils.hasText(message) ? message.trim() : "";
        String lowerMessage = normalized.toLowerCase(Locale.ROOT);

        if (lowerMessage.contains("category=github_token_invalid")) {
            return new FailureSummary(
                "github_token_invalid",
                "GitHub Token 鏃犳晥鎴栧凡杩囨湡",
                "璇峰埌闆嗘垚閰嶇疆椤垫洿鏂?GitHub Token锛岀‘璁よ繛鎺ユ祴璇曢€氳繃鍚庨噸鏂板洖鍐欍€?"
            );
        }
        if (lowerMessage.contains("category=github_permission_denied")) {
            return new FailureSummary(
                "github_permission_denied",
                "GitHub Token 鏉冮檺涓嶈冻",
                "璇风‘璁?Token 瀵圭洰鏍囦粨搴撳叿澶?Pull Request/Issue 璇勮鏉冮檺鍚庨噸鏂板洖鍐欍€?"
            );
        }
        if (lowerMessage.contains("category=github_target_not_found")) {
            return new FailureSummary(
                "github_target_not_found",
                "GitHub PR 鎴栦粨搴撲笉鍙闂?",
                "璇风‘璁や换鍔′粨搴撱€丳R 缂栧彿鍜?Token 鍙闂寖鍥达紝鍐嶉噸鏂板洖鍐欒瘎璁恒€?"
            );
        }
        if (lowerMessage.contains("category=github_rate_limited")) {
            return new FailureSummary(
                "github_rate_limited",
                "GitHub API 璁块棶鍙楅檺",
                "璇风◢鍚庨噸璇曪紝鎴栨洿鎹㈠墿浣欓搴﹀厖瓒崇殑 GitHub Token銆?"
            );
        }
        if (lowerMessage.contains("category=github_timeout")) {
            return new FailureSummary(
                "github_writeback_timeout",
                "GitHub 回写请求超时",
                "请检查网络和 GitHub 服务状态，稍后重新回写。"
            );
        }
        if (lowerMessage.contains("category=github_service_unavailable")) {
            return new FailureSummary(
                "github_service_unavailable",
                "GitHub API 暂时不可用",
                "请稍后重试，并关注 GitHub 服务状态或企业代理网络状态。"
            );
        }
        if (lowerMessage.contains("token is not configured")) {
            return new FailureSummary(
                "github_token_missing",
                "GitHub Token 未配置",
                "请到集成配置页保存 GitHub Token 后重新回写评论。"
            );
        }
        if (lowerMessage.contains("401") || lowerMessage.contains("bad credentials")
            || lowerMessage.contains("unauthorized") || lowerMessage.contains("requires authentication")) {
            return new FailureSummary(
                "github_token_invalid",
                "GitHub Token 无效或已过期",
                "请到集成配置页更新 GitHub Token，确认连接测试通过后重新回写。"
            );
        }
        if (lowerMessage.contains("403") || lowerMessage.contains("forbidden")
            || lowerMessage.contains("resource not accessible") || lowerMessage.contains("permission")) {
            return new FailureSummary(
                "github_permission_denied",
                "GitHub Token 权限不足",
                "请确认 Token 对目标仓库具备 Pull Request/Issue 评论权限后重新回写。"
            );
        }
        if (lowerMessage.contains("404") || lowerMessage.contains("not found")) {
            return new FailureSummary(
                "github_target_not_found",
                "GitHub PR 或仓库不可访问",
                "请确认任务仓库、PR 编号和 Token 可访问范围，再重新回写评论。"
            );
        }
        if (isGithubCommentPositionFailure(lowerMessage)) {
            return new FailureSummary(
                "github_comment_position_invalid",
                "GitHub 行评论定位失败",
                "请检查该审查发现是否仍在 PR Diff 中；必要时改为 PR 总评评论。"
            );
        }
        if (lowerMessage.contains("rate limit")) {
            return new FailureSummary(
                "github_rate_limited",
                "GitHub API 访问受限",
                "请稍后重试，或更换剩余额度充足的 GitHub Token。"
            );
        }
        if (lowerMessage.contains("timeout") || lowerMessage.contains("timed out")) {
            return new FailureSummary(
                "github_writeback_timeout",
                "GitHub 回写请求超时",
                "请检查网络和 GitHub 服务状态，稍后重新回写。"
            );
        }
        if (lowerMessage.contains("owner or repository is not configured")) {
            return new FailureSummary(
                "github_repository_not_configured",
                "GitHub 仓库未配置",
                "请在集成配置中补全默认仓库，或确认任务携带了正确仓库信息。"
            );
        }
        return new FailureSummary(
            "github_writeback_failed",
            "GitHub 评论回写失败",
            "请查看原始错误信息，确认 GitHub 集成配置和目标 PR 状态后重试。"
        );
    }

    private boolean isGithubCommentPositionFailure(String lowerMessage) {
        return lowerMessage.contains("422")
            || lowerMessage.contains("validation failed")
            || lowerMessage.contains("position")
            || lowerMessage.contains("commit_id")
            || lowerMessage.contains("line must")
            || lowerMessage.contains("line is")
            || lowerMessage.contains("line does not")
            || lowerMessage.contains("not part of the diff")
            || lowerMessage.contains("diff hunk");
    }

    private String formatDateTimeOrNull(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME_FORMATTER);
    }

    private record FailureSummary(String category, String reason, String suggestion) {
    }
}
