package com.repoguard.agent.service;

import com.repoguard.agent.dto.FrontendPerformanceReportRequest;

public interface FrontendPerformanceObservationService {

    void record(FrontendPerformanceReportRequest request);
}
