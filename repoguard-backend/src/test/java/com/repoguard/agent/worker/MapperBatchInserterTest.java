package com.repoguard.agent.worker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.mapper.ChangedFileMapper;
import java.util.List;
import java.util.stream.IntStream;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class MapperBatchInserterTest {

    private final SqlSessionFactory sqlSessionFactory = org.mockito.Mockito.mock(SqlSessionFactory.class);
    private final SqlSession sqlSession = org.mockito.Mockito.mock(SqlSession.class);
    private final ChangedFileMapper changedFileMapper = org.mockito.Mockito.mock(ChangedFileMapper.class);
    private final MapperBatchInserter batchInserter = new MapperBatchInserter(sqlSessionFactory);

    @Test
    void insertsAllEntitiesThroughBatchSessionAndFlushesRemainderAfterFullBatch() {
        when(sqlSessionFactory.openSession(ExecutorType.BATCH)).thenReturn(sqlSession);
        when(sqlSession.getMapper(ChangedFileMapper.class)).thenReturn(changedFileMapper);

        batchInserter.insertAll(ChangedFileMapper.class, changedFiles(501));

        InOrder inOrder = org.mockito.Mockito.inOrder(changedFileMapper, sqlSession);
        inOrder.verify(changedFileMapper, org.mockito.Mockito.times(500)).insert(any(ChangedFile.class));
        inOrder.verify(sqlSession).flushStatements();
        inOrder.verify(changedFileMapper).insert(any(ChangedFile.class));
        inOrder.verify(sqlSession).flushStatements();
        inOrder.verify(sqlSession).commit();
        inOrder.verify(sqlSession).close();
        verify(sqlSession, org.mockito.Mockito.times(2)).flushStatements();
        verify(changedFileMapper, org.mockito.Mockito.times(501)).insert(any(ChangedFile.class));
    }

    @Test
    void flushesOnceWhenEntityCountMatchesBatchSize() {
        when(sqlSessionFactory.openSession(ExecutorType.BATCH)).thenReturn(sqlSession);
        when(sqlSession.getMapper(ChangedFileMapper.class)).thenReturn(changedFileMapper);

        batchInserter.insertAll(ChangedFileMapper.class, changedFiles(500));

        verify(changedFileMapper, org.mockito.Mockito.times(500)).insert(any(ChangedFile.class));
        verify(sqlSession, org.mockito.Mockito.times(1)).flushStatements();
        verify(sqlSession).commit();
        verify(sqlSession).close();
    }

    @Test
    void skipsBatchSessionWhenThereAreNoEntities() {
        batchInserter.insertAll(ChangedFileMapper.class, List.of());

        org.mockito.Mockito.verifyNoInteractions(sqlSessionFactory);
    }

    private List<ChangedFile> changedFiles(int count) {
        return IntStream.range(0, count)
            .mapToObj(index -> {
                ChangedFile changedFile = new ChangedFile();
                changedFile.setFilePath("src/File" + index + ".java");
                return changedFile;
            })
            .toList();
    }
}
