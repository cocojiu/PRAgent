package com.repoguard.agent.notification.binding;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.notification.NotificationEventType;
import org.junit.jupiter.api.Test;

class NotificationBindingMatcherTest {

    private final NotificationBindingMatcher matcher = new NotificationBindingMatcher();

    @Test
    void reviewCompletedUsesReviewCompletedSwitch() {
        NotificationChannelBinding binding = binding();
        binding.setNotifyReviewCompleted(true);

        assertThat(matcher.supports(binding, NotificationEventType.REVIEW_COMPLETED.code())).isTrue();

        binding.setNotifyReviewCompleted(false);
        assertThat(matcher.supports(binding, NotificationEventType.REVIEW_COMPLETED.code())).isFalse();
    }

    @Test
    void reviewFailedUsesReviewFailedSwitch() {
        NotificationChannelBinding binding = binding();
        binding.setNotifyReviewFailed(true);

        assertThat(matcher.supports(binding, NotificationEventType.REVIEW_FAILED.code())).isTrue();

        binding.setNotifyReviewFailed(false);
        assertThat(matcher.supports(binding, NotificationEventType.REVIEW_FAILED.code())).isFalse();
    }

    @Test
    void humanReviewRequiredUsesHumanReviewSwitch() {
        NotificationChannelBinding binding = binding();
        binding.setNotifyHumanReviewRequired(true);

        assertThat(matcher.supports(binding, NotificationEventType.HUMAN_REVIEW_REQUIRED.code())).isTrue();

        binding.setNotifyHumanReviewRequired(false);
        assertThat(matcher.supports(binding, NotificationEventType.HUMAN_REVIEW_REQUIRED.code())).isFalse();
    }

    @Test
    void githubCommentPublishedUsesGithubCommentSwitch() {
        NotificationChannelBinding binding = binding();
        binding.setNotifyGithubComment(true);

        assertThat(matcher.supports(binding, NotificationEventType.GITHUB_COMMENT_PUBLISHED.code())).isTrue();

        binding.setNotifyGithubComment(false);
        assertThat(matcher.supports(binding, NotificationEventType.GITHUB_COMMENT_PUBLISHED.code())).isFalse();
    }

    @Test
    void unknownEventTypeIsNotSupported() {
        NotificationChannelBinding binding = binding();
        binding.setNotifyReviewCompleted(true);
        binding.setNotifyReviewFailed(true);
        binding.setNotifyHumanReviewRequired(true);
        binding.setNotifyGithubComment(true);

        assertThat(matcher.supports(binding, "ignored")).isFalse();
        assertThat(matcher.supports(binding, null)).isFalse();
    }

    @Test
    void nullSwitchIsTreatedAsDisabled() {
        NotificationChannelBinding binding = binding();

        assertThat(matcher.supports(binding, NotificationEventType.REVIEW_COMPLETED.code())).isFalse();
    }

    private NotificationChannelBinding binding() {
        return new NotificationChannelBinding();
    }
}
