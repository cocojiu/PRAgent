package com.repoguard.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "repoguard.workflow")
public class ReviewWorkflowProperties {

    private int humanReviewSlaMinutes = 120;
    private int escalationLimit = 3;

    public int getHumanReviewSlaMinutes() {
        return humanReviewSlaMinutes;
    }

    public void setHumanReviewSlaMinutes(int value) {
        if (value < 1 || value > 10080) {
            throw new IllegalArgumentException("humanReviewSlaMinutes must be between 1 and 10080");
        }
        humanReviewSlaMinutes = value;
    }

    public int getEscalationLimit() {
        return escalationLimit;
    }

    public void setEscalationLimit(int value) {
        if (value < 1 || value > 10) {
            throw new IllegalArgumentException("escalationLimit must be between 1 and 10");
        }
        escalationLimit = value;
    }
}
