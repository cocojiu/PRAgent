package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.review.ReviewFindingResult;
import com.repoguard.agent.review.ReviewResult;
import com.repoguard.agent.review.RiskLevelRanker;
import java.util.List;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class ReviewFindingReplacementServiceTest {

    private final ReviewFindingMapper reviewFindingMapper = org.mockito.Mockito.mock(ReviewFindingMapper.class);
    private final ReviewFindingEntityMapper findingEntityMapper = org.mockito.Mockito.mock(ReviewFindingEntityMapper.class);
    private final SqlSessionFactory sqlSessionFactory = org.mockito.Mockito.mock(SqlSessionFactory.class);
    private final SqlSession sqlSession = org.mockito.Mockito.mock(SqlSession.class);
    private final ReviewFindingReplacementService service = new ReviewFindingReplacementService(
        reviewFindingMapper,
        new ReviewFindingDeduplicator(
            new ReviewFindingDeduplicationKeyResolver(),
            new ReviewFindingMergeService(new RiskLevelRanker())
        ),
        findingEntityMapper,
        new MapperBatchInserter(sqlSessionFactory)
    );

    @Test
    void appendsAttemptOwnedDeduplicatedFindingsWithoutDeletingHistory() {
        when(sqlSessionFactory.openSession(ExecutorType.BATCH)).thenReturn(sqlSession);
        when(sqlSession.getMapper(ReviewFindingMapper.class)).thenReturn(reviewFindingMapper);
        ReviewFindingResult firstFinding = new ReviewFindingResult(
            "LOW",
            "LLM",
            "LLM",
            "src/App.java",
            10,
            "Use logger",
            "Replace stdout"
        );
        ReviewFindingResult duplicateFinding = new ReviewFindingResult(
            "HIGH",
            "RULE",
            "RG-JAVA-002",
            "src/App.java",
            10,
            "Use logger",
            "Use structured logger"
        );
        ReviewFindingResult secondFinding = new ReviewFindingResult(
            "MEDIUM",
            "RULE",
            "RG-JAVA-003",
            "src/Task.java",
            20,
            "Avoid sleep",
            "Use awaitility"
        );
        ReviewResult reviewResult = ReviewResult.completed(
            "HIGH",
            List.of(firstFinding, duplicateFinding, secondFinding)
        );
        when(findingEntityMapper.toEntity(eq(42L), eq(101L), any(ReviewFindingResult.class), eq(reviewResult)))
            .thenAnswer(invocation -> finding(invocation.getArgument(2, ReviewFindingResult.class).filePath()));

        int count = service.replace(42L, 101L, reviewResult);

        assertThat(count).isEqualTo(2);
        ArgumentCaptor<ReviewFindingResult> findingResultCaptor = ArgumentCaptor.forClass(ReviewFindingResult.class);
        verify(findingEntityMapper, org.mockito.Mockito.times(2)).toEntity(
            eq(42L),
            eq(101L),
            findingResultCaptor.capture(),
            eq(reviewResult)
        );
        assertThat(findingResultCaptor.getAllValues()).extracting(ReviewFindingResult::filePath).containsExactly(
            "src/App.java",
            "src/Task.java"
        );
        ReviewFindingResult mergedFinding = findingResultCaptor.getAllValues().getFirst();
        assertThat(mergedFinding.severity()).isEqualTo("HIGH");
        assertThat(mergedFinding.source()).isEqualTo("LLM+RULE");
        assertThat(mergedFinding.ruleId()).isEqualTo("LLM / RG-JAVA-002");
        assertThat(mergedFinding.recommendation()).isEqualTo("Replace stdout / Use structured logger");
        assertThat(mergedFinding.confidence()).isEqualTo("HIGH");
        assertThat(mergedFinding.isBlocking()).isFalse();
        assertThat(mergedFinding.fixExample()).isEqualTo("Replace stdout / Use structured logger");
        assertThat(mergedFinding.reviewDimension()).contains("LLM").contains("PROJECT_RULE");
        InOrder inOrder = org.mockito.Mockito.inOrder(reviewFindingMapper, sqlSession);
        ArgumentCaptor<ReviewFinding> findingCaptor = ArgumentCaptor.forClass(ReviewFinding.class);
        inOrder.verify(reviewFindingMapper, org.mockito.Mockito.times(2)).insert(findingCaptor.capture());
        inOrder.verify(sqlSession).flushStatements();
        inOrder.verify(sqlSession).close();
        verify(reviewFindingMapper, never()).delete(any());
        assertThat(findingCaptor.getAllValues()).extracting(ReviewFinding::getFilePath).containsExactly(
            "src/App.java",
            "src/Task.java"
        );
    }

    @Test
    void emptyAttemptDoesNotDeleteHistory() {
        int count = service.replace(42L, 101L, ReviewResult.completed("INFO", List.of()));

        assertThat(count).isZero();
        verify(reviewFindingMapper, never()).delete(any());
        verify(reviewFindingMapper, org.mockito.Mockito.never()).insert(any(ReviewFinding.class));
        org.mockito.Mockito.verifyNoInteractions(sqlSessionFactory);
    }

    private ReviewFinding finding(String filePath) {
        ReviewFinding finding = new ReviewFinding();
        finding.setFilePath(filePath);
        return finding;
    }
}
