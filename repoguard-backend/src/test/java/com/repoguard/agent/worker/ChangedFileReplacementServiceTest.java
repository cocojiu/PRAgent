package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestDiff;
import com.repoguard.agent.mapper.ChangedFileMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ChangedFileReplacementServiceTest {

    private final ChangedFileMapper changedFileMapper = org.mockito.Mockito.mock(ChangedFileMapper.class);
    private final ChangedFileReplacementService service = new ChangedFileReplacementService(changedFileMapper);

    @Test
    void deletesExistingFilesAndStoresChangedFilesFromDiff() {
        GithubPullRequestDiff diff = new GithubPullRequestDiff(
            "octocat",
            "Hello-World",
            7,
            List.of(
                new GithubChangedFile("src/New.java", "added", 3, null, "+class New {}"),
                new GithubChangedFile("src/Old.java", "removed", null, 2, "-class Old {}"),
                new GithubChangedFile("src/Renamed.java", "renamed", 1, 1, "rename"),
                new GithubChangedFile("src/App.java", "modified", 4, 5, "patch"),
                new GithubChangedFile("src/Unknown.java", null, null, null, "patch")
            )
        );

        service.replace(42L, diff);

        verify(changedFileMapper).delete(any());
        ArgumentCaptor<ChangedFile> fileCaptor = ArgumentCaptor.forClass(ChangedFile.class);
        verify(changedFileMapper, org.mockito.Mockito.times(5)).insert(fileCaptor.capture());
        assertThat(fileCaptor.getAllValues()).extracting(ChangedFile::getTaskId).containsOnly(42L);
        assertThat(fileCaptor.getAllValues()).extracting(ChangedFile::getFilePath).containsExactly(
            "src/New.java",
            "src/Old.java",
            "src/Renamed.java",
            "src/App.java",
            "src/Unknown.java"
        );
        assertThat(fileCaptor.getAllValues()).extracting(ChangedFile::getChangeType).containsExactly(
            "ADD",
            "DELETE",
            "RENAME",
            "MODIFY",
            "MODIFY"
        );
        assertThat(fileCaptor.getAllValues()).extracting(ChangedFile::getAdditions).containsExactly(3, 0, 1, 4, 0);
        assertThat(fileCaptor.getAllValues()).extracting(ChangedFile::getDeletions).containsExactly(0, 2, 1, 5, 0);
    }
}
