package com.repoguard.agent.dto;

import java.util.List;

/**
 * 任务详情页使用的 GitHub 评论回写历史。
 *
 * <p>按批次倒序返回，每个批次包含当次操作的汇总和逐条审查发现结果。
 */
public record GithubCommentPublicationHistoryResponse(
    Long taskId,
    List<GithubCommentPublicationBatchDto> batches
) {
}
