package com.repoguard.agent.service.impl;

import com.repoguard.agent.service.DashboardService;
import org.springframework.stereotype.Service;

@Service
public class DashboardServiceImpl implements DashboardService {

    @Override
    public String ping() {
        return "dashboard-service-ready";
    }
}
