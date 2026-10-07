package com.repoguard.agent.dto;

public record CodeownersAcceptResponse(Long taskId, Long attemptId, String headSha, String assignee) { }
