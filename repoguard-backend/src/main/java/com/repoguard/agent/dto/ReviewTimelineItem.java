package com.repoguard.agent.dto;

/**
 * 评审执行时间线中的一个步骤。
 */
public record ReviewTimelineItem(
    String label,
    String time,
    String status
) {
}
