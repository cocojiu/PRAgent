package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.review.PullRequestChangedFile;
import org.junit.jupiter.api.Test;

class ChangedFileEntityMapperTest {

    private final ChangedFileEntityMapper mapper = new ChangedFileEntityMapper();

    @Test
    void mapsGithubChangedFileToChangedFileEntity() {
        ChangedFile changedFile = mapper.toEntity(
            42L,
            new PullRequestChangedFile("src/App.java", "added", 3, 2, "+class App {}")
        );

        assertThat(changedFile.getTaskId()).isEqualTo(42L);
        assertThat(changedFile.getFilePath()).isEqualTo("src/App.java");
        assertThat(changedFile.getChangeType()).isEqualTo("ADD");
        assertThat(changedFile.getAdditions()).isEqualTo(3);
        assertThat(changedFile.getDeletions()).isEqualTo(2);
    }

    @Test
    void normalizesStatusesAndMissingLineCounts() {
        assertThat(mapper.toEntity(1L, changedFile("added", null, null)).getChangeType()).isEqualTo("ADD");
        assertThat(mapper.toEntity(1L, changedFile("removed", null, null)).getChangeType()).isEqualTo("DELETE");
        assertThat(mapper.toEntity(1L, changedFile("renamed", null, null)).getChangeType()).isEqualTo("RENAME");
        assertThat(mapper.toEntity(1L, changedFile("modified", null, null)).getChangeType()).isEqualTo("MODIFY");
        assertThat(mapper.toEntity(1L, changedFile(null, null, null)).getChangeType()).isEqualTo("MODIFY");
        assertThat(mapper.toEntity(1L, changedFile("added", null, null)).getAdditions()).isZero();
        assertThat(mapper.toEntity(1L, changedFile("added", null, null)).getDeletions()).isZero();
    }

    private PullRequestChangedFile changedFile(String status, Integer additions, Integer deletions) {
        return new PullRequestChangedFile("src/App.java", status, additions, deletions, "patch");
    }
}
