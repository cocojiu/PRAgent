package com.repoguard.agent.dto;

import java.time.LocalDateTime;

public record UserManagementItemDto(
    Long id,
    String username,
    String email,
    String role,
    String status,
    Integer failedLoginCount,
    LocalDateTime lockedUntil,
    LocalDateTime lastLoginAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
