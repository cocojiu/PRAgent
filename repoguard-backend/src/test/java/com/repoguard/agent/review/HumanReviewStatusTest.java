package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HumanReviewStatusTest {

    @Test
    void fromNormalizesStoredStatusCodes() {
        assertThat(HumanReviewStatus.from("pending")).isEqualTo(HumanReviewStatus.PENDING);
        assertThat(HumanReviewStatus.from(" changes_requested ")).isEqualTo(HumanReviewStatus.CHANGES_REQUESTED);
        assertThat(HumanReviewStatus.from("not_required")).isEqualTo(HumanReviewStatus.NOT_REQUIRED);
        assertThat(HumanReviewStatus.from(null)).isEqualTo(HumanReviewStatus.UNKNOWN);
        assertThat(HumanReviewStatus.from("done")).isEqualTo(HumanReviewStatus.UNKNOWN);
    }

    @Test
    void fromActionMapsSupportedUserActions() {
        assertThat(HumanReviewStatus.fromAction("approve")).isEqualTo(HumanReviewStatus.APPROVED);
        assertThat(HumanReviewStatus.fromAction("changes_requested")).isEqualTo(HumanReviewStatus.CHANGES_REQUESTED);
        assertThat(HumanReviewStatus.fromAction("reject")).isEqualTo(HumanReviewStatus.REJECTED);
        assertThat(HumanReviewStatus.fromAction("skip")).isEqualTo(HumanReviewStatus.UNKNOWN);
    }

    @Test
    void githubCommentPublishRequiresAcceptedHumanReviewDecision() {
        assertThat(HumanReviewStatus.APPROVED.allowsGithubCommentPublish()).isTrue();
        assertThat(HumanReviewStatus.CHANGES_REQUESTED.allowsGithubCommentPublish()).isTrue();
        assertThat(HumanReviewStatus.PENDING.allowsGithubCommentPublish()).isFalse();
        assertThat(HumanReviewStatus.REJECTED.allowsGithubCommentPublish()).isFalse();
    }
}
