package com.repoguard.agent.dto;

public record ReviewMemberAssignmentResponse(Long taskId, Long attemptId, String headSha, String assignee) { }
