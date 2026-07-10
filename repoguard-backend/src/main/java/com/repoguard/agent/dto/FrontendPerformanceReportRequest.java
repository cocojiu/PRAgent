package com.repoguard.agent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;

public record FrontendPerformanceReportRequest(
    @Size(max = 80)
    String route,

    @Valid
    @Size(max = 50)
    List<FrontendApiWaterfallItemDto> apiRequests,

    @Valid
    @Size(max = 50)
    List<FrontendLongTaskItemDto> longTasks
) {
}
