package com.repoguard.agent.dto;

import java.util.List;

/**
 * PR 评审详情的完整只读响应。
 */
public record ReviewTaskDetail(
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
    String createdAt,
    String duration,
    String prUrl,
    List<ReviewFindingDto> findings,
    List<MissingTestDto> missingTests,
    List<ChangedFileDto> changedFiles,
    List<ReviewTimelineItem> timeline,
    LlmStatusDto llm,
    RabbitMqStatusDto rabbitMq
) {
}
