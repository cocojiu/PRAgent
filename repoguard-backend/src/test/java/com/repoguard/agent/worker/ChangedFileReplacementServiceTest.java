package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
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
    private final ChangedFileEntityMapper changedFileEntityMapper = org.mockito.Mockito.mock(ChangedFileEntityMapper.class);
    private final ChangedFileReplacementService service = new ChangedFileReplacementService(
        changedFileMapper,
        changedFileEntityMapper
    );

    @Test
    void deletesExistingFilesAndStoresMappedChangedFilesFromDiff() {
        GithubChangedFile firstFile = new GithubChangedFile("src/New.java", "added", 3, null, "+class New {}");
        GithubChangedFile secondFile = new GithubChangedFile("src/Old.java", "removed", null, 2, "-class Old {}");
        GithubPullRequestDiff diff = new GithubPullRequestDiff(
            "octocat",
            "Hello-World",
            7,
            List.of(firstFile, secondFile)
        );
        ChangedFile firstEntity = changedFile("src/New.java");
        ChangedFile secondEntity = changedFile("src/Old.java");
        when(changedFileEntityMapper.toEntity(42L, firstFile)).thenReturn(firstEntity);
        when(changedFileEntityMapper.toEntity(42L, secondFile)).thenReturn(secondEntity);

        service.replace(42L, diff);

        verify(changedFileMapper).delete(any());
        verify(changedFileEntityMapper).toEntity(42L, firstFile);
        verify(changedFileEntityMapper).toEntity(42L, secondFile);
        ArgumentCaptor<ChangedFile> fileCaptor = ArgumentCaptor.forClass(ChangedFile.class);
        verify(changedFileMapper, org.mockito.Mockito.times(2)).insert(fileCaptor.capture());
        assertThat(fileCaptor.getAllValues()).containsExactly(firstEntity, secondEntity);
    }

    private ChangedFile changedFile(String filePath) {
        ChangedFile changedFile = new ChangedFile();
        changedFile.setFilePath(filePath);
        return changedFile;
    }
}
