package com.repoguard.agent.service.impl;

import com.repoguard.agent.config.GithubIntegrationProvider;
import com.repoguard.agent.config.ReviewPolicyProvider;
import com.repoguard.agent.dto.SystemHealthItemDto;
import java.util.List;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
class DashboardSystemHealthProbe {

    private final GithubIntegrationProvider githubIntegrationProvider;
    private final ReviewPolicyProvider reviewPolicyProvider;
    private final RabbitTemplate rabbitTemplate;
    private final DashboardStatusMapper statusMapper;

    DashboardSystemHealthProbe(
        GithubIntegrationProvider githubIntegrationProvider,
        ReviewPolicyProvider reviewPolicyProvider,
        RabbitTemplate rabbitTemplate,
        DashboardStatusMapper statusMapper
    ) {
        this.githubIntegrationProvider = githubIntegrationProvider;
        this.reviewPolicyProvider = reviewPolicyProvider;
        this.rabbitTemplate = rabbitTemplate;
        this.statusMapper = statusMapper;
    }

    List<SystemHealthItemDto> probe() {
        return List.of(
            new SystemHealthItemDto("MySQL", DashboardStatusMapper.HEALTH_NORMAL),
            new SystemHealthItemDto("RabbitMQ", rabbitMqHealthStatus()),
            new SystemHealthItemDto("GitHub", githubHealthStatus()),
            new SystemHealthItemDto("Spring AI", llmHealthStatus())
        );
    }

    private String rabbitMqHealthStatus() {
        try {
            Boolean open = rabbitTemplate.execute(channel -> channel.isOpen());
            return statusMapper.rabbitMqHealth(open);
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
