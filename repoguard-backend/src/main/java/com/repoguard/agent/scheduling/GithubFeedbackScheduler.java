package com.repoguard.agent.scheduling;

import com.repoguard.agent.config.SchedulerRuntimeEnabled;
import com.repoguard.agent.github.webhook.GithubFeedbackService;
import com.repoguard.agent.tenancy.TenantScheduledTaskRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@SchedulerRuntimeEnabled
public class GithubFeedbackScheduler {
    private final GithubFeedbackService feedback;
    private final TenantScheduledTaskRunner tenants;

    public GithubFeedbackScheduler(GithubFeedbackService feedback, TenantScheduledTaskRunner tenants) {
        this.feedback = feedback;
        this.tenants = tenants;
    }

    @Scheduled(fixedDelayString = "${app.github.webhook.feedback-poll-interval-ms:5000}")
    public void processFeedback() {
        if (feedback.enabled()) tenants.runForEachActiveTenant("github_feedback", feedback::processDue);
    }
}
