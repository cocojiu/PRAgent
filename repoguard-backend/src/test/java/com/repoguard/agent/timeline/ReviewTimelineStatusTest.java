package com.repoguard.agent.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReviewTimelineStatusTest {

    @Test
    void mapsPersistentStatusToDisplayStatus() {
        assertThat(ReviewTimelineStatus.from("done")).isEqualTo(ReviewTimelineStatus.DONE);
        assertThat(ReviewTimelineStatus.from(" CURRENT ")).isEqualTo(ReviewTimelineStatus.CURRENT);
        assertThat(ReviewTimelineStatus.from("failed")).isEqualTo(ReviewTimelineStatus.FAILED);
        assertThat(ReviewTimelineStatus.FAILED.displayStatus()).isEqualTo("done");
    }

    @Test
    void unknownStatusFallsBackToPendingDisplayStatus() {
        assertThat(ReviewTimelineStatus.from(null)).isEqualTo(ReviewTimelineStatus.UNKNOWN);
        assertThat(ReviewTimelineStatus.from("")).isEqualTo(ReviewTimelineStatus.UNKNOWN);
        assertThat(ReviewTimelineStatus.from("waiting").displayStatus()).isEqualTo("pending");
    }
}
