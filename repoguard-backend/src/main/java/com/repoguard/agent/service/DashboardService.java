package com.repoguard.agent.service;

import com.repoguard.agent.dto.DashboardOverviewResponse;

public interface DashboardService {

    /**
     * 基于已持久化的评审任务和问题记录构建仪表盘概览。
     */
    DashboardOverviewResponse getOverview(Integer llmTrendDays);
}
