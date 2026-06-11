package com.repoguard.agent.dto;

import java.time.LocalDateTime;

public record AuthCurrentUserDto(
    Long id,
    String username,
    String email,
    String role,
    String status,
    LocalDateTime lastLoginAt
) {
}
