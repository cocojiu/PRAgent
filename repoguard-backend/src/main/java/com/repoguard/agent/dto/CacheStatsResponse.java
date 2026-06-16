package com.repoguard.agent.dto;

import java.util.List;

public record CacheStatsResponse(
    List<CacheStatsItemDto> caches
) {
}
