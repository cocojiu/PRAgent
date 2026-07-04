package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.dto.CacheStatsResponse;
import com.repoguard.agent.service.CacheStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cache")
@ApiRuntimeEnabled
public class CacheStatsController {

    private final CacheStatsService cacheStatsService;

    public CacheStatsController(CacheStatsService cacheStatsService) {
        this.cacheStatsService = cacheStatsService;
    }

    @GetMapping("/stats")
    public ApiResponse<CacheStatsResponse> getStats() {
        return ApiResponse.ok(cacheStatsService.getStats());
    }
}
