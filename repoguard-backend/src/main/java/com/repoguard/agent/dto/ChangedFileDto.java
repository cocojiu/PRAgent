package com.repoguard.agent.dto;

/**
 * 评审详情中展示的文件级变更摘要。
 */
public record ChangedFileDto(
    String path,
    String changeType,
    Integer additions,
    Integer deletions,
    Long findingCount
) {
    public ChangedFileDto(String path, String changeType, Integer additions, Integer deletions) {
        this(path, changeType, additions, deletions, null);
    }
}
