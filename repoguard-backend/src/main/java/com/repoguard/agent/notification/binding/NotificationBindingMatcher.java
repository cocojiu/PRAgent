package com.repoguard.agent.notification.binding;

import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.notification.NotificationEventType;
import org.springframework.stereotype.Component;

@Component
public class NotificationBindingMatcher {

    public boolean supports(NotificationChannelBinding binding, String eventType) {
        return switch (NotificationEventType.from(eventType)) {
            case REVIEW_COMPLETED -> Boolean.TRUE.equals(binding.getNotifyReviewCompleted());
            case REVIEW_FAILED -> Boolean.TRUE.equals(binding.getNotifyReviewFailed());
            case HUMAN_REVIEW_REQUIRED -> Boolean.TRUE.equals(binding.getNotifyHumanReviewRequired());
            case GITHUB_COMMENT_PUBLISHED -> Boolean.TRUE.equals(binding.getNotifyGithubComment());
            case MODEL_RELEASE_ALERT -> Boolean.TRUE.equals(binding.getNotifyReviewFailed());
            case UNKNOWN -> false;
        };
    }
}
