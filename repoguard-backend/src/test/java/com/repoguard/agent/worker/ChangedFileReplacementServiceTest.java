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
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class ChangedFileReplacementServiceTest {

    private final ChangedFileMapper changedFileMapper = org.mockito.Mockito.mock(ChangedFileMapper.class);
    private final ChangedFileEntityMapper changedFileEntityMapper = org.mockito.Mockito.mock(ChangedFileEntityMapper.class);
    private final SqlSessionFactory sqlSessionFactory = org.mockito.Mockito.mock(SqlSessionFactory.class);
    private final SqlSession sqlSession = org.mockito.Mockito.mock(SqlSession.class);
    private final ChangedFileReplacementService service = new ChangedFileReplacementService(
        changedFileMapper,
        changedFileEntityMapper,
        new MapperBatchInserter(sqlSessionFactory)
    );

    @Test
    void deletesExistingFilesAndStoresMappedChangedFilesFromDiff() {
        when(sqlSessionFactory.openSession(ExecutorType.BATCH)).thenReturn(sqlSession);
        when(sqlSession.getMapper(ChangedFileMapper.class)).thenReturn(changedFileMapper);
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

        verify(changedFileEntityMapper).toEntity(42L, firstFile);
        verify(changedFileEntityMapper).toEntity(42L, secondFile);
        InOrder inOrder = org.mockito.Mockito.inOrder(changedFileMapper, sqlSession);
        inOrder.verify(changedFileMapper).delete(any());
        ArgumentCaptor<ChangedFile> fileCaptor = ArgumentCaptor.forClass(ChangedFile.class);
        inOrder.verify(changedFileMapper, org.mockito.Mockito.times(2)).insert(fileCaptor.capture());
        inOrder.verify(sqlSession).flushStatements();
        inOrder.verify(sqlSession).close();
        assertThat(fileCaptor.getAllValues()).containsExactly(firstEntity, secondEntity);
    }

    @Test
    void deletesExistingFilesWithoutOpeningBatchSessionWhenDiffHasNoFiles() {
        service.replace(42L, new GithubPullRequestDiff("octocat", "Hello-World", 7, List.of()));

        verify(changedFileMapper).delete(any());
        org.mockito.Mockito.verifyNoInteractions(sqlSessionFactory);
    }

    private ChangedFile changedFile(String filePath) {
        ChangedFile changedFile = new ChangedFile();
        changedFile.setFilePath(filePath);
        return changedFile;
    }
}
