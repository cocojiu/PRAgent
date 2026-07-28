package com.repoguard.agent.dashboard;

import com.repoguard.agent.github.GithubIntegrationProvider;
import com.repoguard.agent.review.ReviewPolicyProvider;
import com.repoguard.agent.dto.SystemHealthItemDto;
import com.repoguard.agent.messaging.RabbitRuntimeHealthProbe;
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
            return statusMapper.rabbitMqHealth(rabbitRuntimeHealthProbe.connectionStatus());
        } catch (RuntimeException ex) {
            return DashboardStatusMapper.HEALTH_UNKNOWN;
        }
    }

    private String githubHealthStatus() {
        try {
            return statusMapper.githubHealth(githubIntegrationProvider.getSettings());
        } catch (RuntimeException ex) {
            return DashboardStatusMapper.HEALTH_UNKNOWN;
        }
    }

    private String llmHealthStatus() {
        try {
            return statusMapper.llmHealth(reviewPolicyProvider.getSettings());
        } catch (RuntimeException ex) {
            return DashboardStatusMapper.HEALTH_UNKNOWN;
        }
    }
}
