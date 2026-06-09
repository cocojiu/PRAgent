package com.repoguard.agent.dto;

/**
 * 评审任务列表接口返回的摘要行。
 */
public record ReviewTaskListItem(
    Long id,
    Integer prNumber,
    String title,
    String repository,
    String organization,
    String commit,
    String branch,
    String status,
    String riskLevel,
    Integer mqRetries,
    String llmStatus,
    String source,
    String triggerSource,
    String createdAt,
    String duration,
    String failureCategory,
    String failureReason,
    String failureSuggestion
) {
}
