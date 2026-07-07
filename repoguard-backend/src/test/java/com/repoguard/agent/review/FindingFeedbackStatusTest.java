package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.entity.ReviewFinding;
import org.junit.jupiter.api.Test;

class FindingFeedbackStatusTest {

    @Test
    void blankStatusDefaultsToUnreviewed() {
        assertThat(FindingFeedbackStatus.from(null)).isEqualTo(FindingFeedbackStatus.UNREVIEWED);
        assertThat(FindingFeedbackStatus.from(" ")).isEqualTo(FindingFeedbackStatus.UNREVIEWED);
    }

    @Test
    void parsesKnownStatusesIgnoringCase() {
        assertThat(FindingFeedbackStatus.from("valid")).isEqualTo(FindingFeedbackStatus.VALID);
        assertThat(FindingFeedbackStatus.from(" false_positive ")).isEqualTo(FindingFeedbackStatus.FALSE_POSITIVE);
        assertThat(FindingFeedbackStatus.from("FIXED")).isEqualTo(FindingFeedbackStatus.FIXED);
        assertThat(FindingFeedbackStatus.from("ignored")).isEqualTo(FindingFeedbackStatus.IGNORED);
    }

    @Test
    void exposesDtoCodeAndCommentablePolicy() {
        assertThat(FindingFeedbackStatus.FALSE_POSITIVE.dtoCode()).isEqualTo("false_positive");
        assertThat(FindingFeedbackStatus.UNREVIEWED.commentable()).isTrue();
        assertThat(FindingFeedbackStatus.VALID.commentable()).isTrue();
        assertThat(FindingFeedbackStatus.FALSE_POSITIVE.commentable()).isFalse();
        assertThat(FindingFeedbackStatus.FIXED.commentable()).isFalse();
        assertThat(FindingFeedbackStatus.IGNORED.commentable()).isFalse();
        assertThat(FindingFeedbackStatus.UNKNOWN.commentable()).isFalse();
    }

    @Test
    void readsStatusFromFindingWithDefault() {
        ReviewFinding finding = new ReviewFinding();
        assertThat(FindingFeedbackStatus.fromFinding(finding)).isEqualTo(FindingFeedbackStatus.UNREVIEWED);

        finding.setFeedbackStatus("valid");
        assertThat(FindingFeedbackStatus.fromFinding(finding)).isEqualTo(FindingFeedbackStatus.VALID);
    }

    @Test
    void unknownStoredStatusIsNotCommentable() {
        ReviewFinding finding = new ReviewFinding();
        finding.setFeedbackStatus("custom");

        assertThat(FindingFeedbackStatus.fromFinding(finding)).isEqualTo(FindingFeedbackStatus.UNKNOWN);
        assertThat(FindingFeedbackStatus.fromFinding(finding).dtoCode()).isEqualTo("unknown");
        assertThat(FindingFeedbackStatus.fromFinding(finding).commentable()).isFalse();
    }

    @Test
    void normalizesQueryStatusWithoutRejectingUnknownValue() {
        assertThat(FindingFeedbackStatus.queryCode(null)).isEqualTo("UNREVIEWED");
        assertThat(FindingFeedbackStatus.queryCode(" valid ")).isEqualTo("VALID");
        assertThat(FindingFeedbackStatus.queryCode("unknown")).isEqualTo("UNKNOWN");
    }

    @Test
    void rejectsUnsupportedStatusForStrictParsing() {
        assertThatThrownBy(() -> FindingFeedbackStatus.from("maybe"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Unsupported finding feedback status: maybe");
        assertThatThrownBy(() -> FindingFeedbackStatus.from("unknown"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Unsupported finding feedback status: unknown");
    }
}
