package com.repoguard.agent.dto;

import java.util.List;

public record ReviewAssignmentOptionsResponse(Long taskId, Long attemptId, String headSha, String assignmentVersion,
                                             List<Member> members, boolean hasMore) {
    public record Member(Long userId, String username) { }
}
