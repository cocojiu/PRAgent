package com.repoguard.agent.dto;

public record NotificationItemDto(
    String id,
    String level,
    String title,
    String description,
    String time,
    String targetPath,
    String createdAt
) {
}
