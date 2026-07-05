package com.repoguard.agent.dto;

import java.util.List;

public record FrontendPerformanceReportRequest(
    String route,
    List<FrontendApiWaterfallItemDto> apiRequests,
    List<FrontendLongTaskItemDto> longTasks
) {
}
