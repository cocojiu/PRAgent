package com.repoguard.agent.dto;

import java.util.List;

public record CodeownersRecommendationResponse(String status, Long taskId, Long attemptId, String headSha,
    String baseSha, String sourcePath, List<CodeownersCandidate> candidates, List<CodeownersPath> basis,
    List<String> uncoveredPaths, List<String> unmappedIdentities) {
    public record CodeownersCandidate(Long userId, String username, int coveredFiles, int findingCount,
        int riskScore, List<String> externalIdentities, List<String> paths) { }
    public record CodeownersPath(String path, String pattern, int line, List<String> owners, List<String> changedFiles) { }
}
