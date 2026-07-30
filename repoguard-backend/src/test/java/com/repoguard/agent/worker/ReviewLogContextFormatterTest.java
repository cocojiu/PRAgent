package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.task.ReviewTaskMessage;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ReviewLogContextFormatterTest {

    private final ReviewLogContextFormatter formatter = new ReviewLogContextFormatter();

    @Test
    void formatsTaskRepositorySlugWithTrimmedParts() {
        ReviewTask task = new ReviewTask();
        task.setOrganization(" octocat ");
        task.setRepository(" Hello-World ");

        assertThat(formatter.repositorySlug(task)).isEqualTo("octocat/Hello-World");
    }

    @Test
    void formatsMessageRepositorySlugWithUnknownParts() {
        ReviewTaskMessage message = new ReviewTaskMessage(
            42L,
            " ",
            null,
            7,
            " a1b2c3d ",
            LocalDateTime.parse("2026-07-05T00:00:00")
        );

        assertThat(formatter.repositorySlug(message)).isEqualTo("<unknown>/<unknown>");
        assertThat(formatter.safePart(message.commit())).isEqualTo("a1b2c3d");
    }
}
