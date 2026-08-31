package com.repoguard.agent.dto;

import java.time.LocalDateTime;

public record AuthCurrentUserDto(
    Long id,
    String username,
    String email,
    String role,
    String status,
    LocalDateTime lastLoginAt,
    String language,
    String timezone
) {
    public AuthCurrentUserDto(
        Long id,
        String username,
        String email,
        String role,
        String status,
        LocalDateTime lastLoginAt
    ) {
        this(id, username, email, role, status, lastLoginAt, "zh-CN", "Asia/Shanghai");
    }
}
