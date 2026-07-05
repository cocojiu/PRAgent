package com.repoguard.agent.service;

import com.repoguard.agent.dto.DashboardOverviewResponse;
import com.repoguard.agent.dto.SystemHealthItemDto;
import java.util.List;

public interface DashboardService {

    /**
     * 基于已持久化的评审任务和问题记录构建仪表盘概览。
     */
    DashboardOverviewResponse getOverview(Integer llmTrendDays);

    List<SystemHealthItemDto> getSystemHealth();
}
