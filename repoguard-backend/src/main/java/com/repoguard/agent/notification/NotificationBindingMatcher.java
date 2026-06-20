package com.repoguard.agent.notification;

import com.repoguard.agent.entity.NotificationChannelBinding;
import org.springframework.stereotype.Component;

@Component
class NotificationBindingMatcher {

    boolean supports(NotificationChannelBinding binding, String eventType) {
        return switch (NotificationEventType.from(eventType)) {
            case REVIEW_COMPLETED -> Boolean.TRUE.equals(binding.getNotifyReviewCompleted());
            case REVIEW_FAILED -> Boolean.TRUE.equals(binding.getNotifyReviewFailed());
            case HUMAN_REVIEW_REQUIRED -> Boolean.TRUE.equals(binding.getNotifyHumanReviewRequired());
            case GITHUB_COMMENT_PUBLISHED -> Boolean.TRUE.equals(binding.getNotifyGithubComment());
            case UNKNOWN -> false;
        };
    }
}
