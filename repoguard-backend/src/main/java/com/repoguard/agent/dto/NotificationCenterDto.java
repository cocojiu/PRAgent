package com.repoguard.agent.dto;

import java.util.List;

public record NotificationCenterDto(
    int total,
    String generatedAt,
    List<NotificationItemDto> items
) {
}
