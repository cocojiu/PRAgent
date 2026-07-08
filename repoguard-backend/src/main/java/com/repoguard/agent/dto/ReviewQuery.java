package com.repoguard.agent.dto;

/**
 * Controller 校验后的评审列表查询参数。
 */
public record ReviewQuery(
    int page,
    int pageSize,
    String repository,
    String status,
    String riskLevel,
    String source,
    String triggerSource,
    String keyword,
    String cursorCreatedAt,
    Long cursorId
) {
    public ReviewQuery(
        int page,
        int pageSize,
        String repository,
        String status,
        String riskLevel,
        String source,
        String triggerSource,
        String keyword
    ) {
        this(page, pageSize, repository, status, riskLevel, source, triggerSource, keyword, null, null);
    }
}
