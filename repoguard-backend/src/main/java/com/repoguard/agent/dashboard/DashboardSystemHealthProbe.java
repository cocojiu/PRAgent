package com.repoguard.agent.dashboard;

import com.repoguard.agent.config.GithubIntegrationProvider;
import com.repoguard.agent.config.ReviewPolicyProvider;
import com.repoguard.agent.dto.SystemHealthItemDto;
import com.repoguard.agent.service.impl.RabbitRuntimeHealthProbe;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DashboardSystemHealthProbe {

    private final GithubIntegrationProvider githubIntegrationProvider;
    private final ReviewPolicyProvider reviewPolicyProvider;
    private final RabbitRuntimeHealthProbe rabbitRuntimeHealthProbe;
    private final DashboardStatusMapper statusMapper;

    public DashboardSystemHealthProbe(
        GithubIntegrationProvider githubIntegrationProvider,
        ReviewPolicyProvider reviewPolicyProvider,
        RabbitRuntimeHealthProbe rabbitRuntimeHealthProbe,
        DashboardStatusMapper statusMapper
    ) {
        this.githubIntegrationProvider = githubIntegrationProvider;
        this.reviewPolicyProvider = reviewPolicyProvider;
        this.rabbitRuntimeHealthProbe = rabbitRuntimeHealthProbe;
        this.statusMapper = statusMapper;
    }

    public List<SystemHealthItemDto> probe() {
        return List.of(
            new SystemHealthItemDto("MySQL", DashboardStatusMapper.HEALTH_NORMAL),
            new SystemHealthItemDto("RabbitMQ", rabbitMqHealthStatus()),
            new SystemHealthItemDto("GitHub", githubHealthStatus()),
            new SystemHealthItemDto("Spring AI", llmHealthStatus())
        );
    }

    private String rabbitMqHealthStatus() {
        try {
            return statusMapper.rabbitMqHealth("CONNECTED".equals(rabbitRuntimeHealthProbe.connectionStatus()));
        } catch (RuntimeException ex) {
            return DashboardStatusMapper.HEALTH_ABNORMAL;
        }
    }

    private String githubHealthStatus() {
        try {
            return statusMapper.githubHealth(githubIntegrationProvider.getSettings());
        } catch (RuntimeException ex) {
            return DashboardStatusMapper.HEALTH_ABNORMAL;
        }
    }

    private String llmHealthStatus() {
        try {
            return statusMapper.llmHealth(reviewPolicyProvider.getSettings());
        } catch (RuntimeException ex) {
            return DashboardStatusMapper.HEALTH_ABNORMAL;
        }
    }
}
