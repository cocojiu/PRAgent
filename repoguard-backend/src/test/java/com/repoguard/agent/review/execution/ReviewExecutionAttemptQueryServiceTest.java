package com.repoguard.agent.review.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.ReviewExecutionAttempt;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.ReviewExecutionAttemptMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewExecutionAttemptQueryServiceTest {

    private final ReviewTaskMapper taskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final ReviewExecutionAttemptMapper attemptMapper = org.mockito.Mockito.mock(ReviewExecutionAttemptMapper.class);
    private final ChangedFileMapper changedFileMapper = org.mockito.Mockito.mock(ChangedFileMapper.class);
    private final ReviewFindingMapper findingMapper = org.mockito.Mockito.mock(ReviewFindingMapper.class);
    private final ReviewExecutionAttemptQueryService service = new ReviewExecutionAttemptQueryService(
        taskMapper,
        attemptMapper,
        changedFileMapper,
        findingMapper
    );

    @Test
    void returnsHistoricalFilesAndFindingsOwnedByRequestedAttempt() {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        task.setCurrentAttemptId(102L);
        ReviewExecutionAttempt attempt = attempt(101L, 42L, 1);
        ChangedFile file = new ChangedFile();
        file.setId(201L);
        file.setFilePath("src/App.java");
        ReviewFinding finding = new ReviewFinding();
        finding.setId(301L);
        finding.setCategory("FINDING");
        finding.setMessage("old result");
        when(taskMapper.selectById(42L)).thenReturn(task);
        when(attemptMapper.selectById(101L)).thenReturn(attempt);
        Page<ChangedFile> filePage = Page.of(1, 50);
        filePage.setRecords(List.of(file));
        filePage.setTotal(1);
        Page<ReviewFinding> findingPage = Page.of(1, 50);
        findingPage.setRecords(List.of(finding));
        findingPage.setTotal(1);
        when(changedFileMapper.selectPage(
            org.mockito.ArgumentMatchers.<Page<ChangedFile>>any(),
            org.mockito.ArgumentMatchers.<Wrapper<ChangedFile>>any()
        )).thenReturn(filePage);
        when(findingMapper.selectPage(
            org.mockito.ArgumentMatchers.<Page<ReviewFinding>>any(),
            org.mockito.ArgumentMatchers.<Wrapper<ReviewFinding>>any()
        )).thenReturn(findingPage);

        var result = service.getResult(42L, 101L, 1, 50);

        assertThat(result.attempt().current()).isFalse();
        assertThat(result.changedFiles().items()).extracting(item -> item.path()).containsExactly("src/App.java");
        assertThat(result.findings().items()).extracting(item -> item.message()).containsExactly("old result");
    }

    @Test
    void rejectsAttemptOwnedByAnotherTask() {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        when(taskMapper.selectById(42L)).thenReturn(task);
        when(attemptMapper.selectById(101L)).thenReturn(attempt(101L, 99L, 1));

        assertThatThrownBy(() -> service.getResult(42L, 101L, 1, 50))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("attempt not found");
    }

    private ReviewExecutionAttempt attempt(Long id, Long taskId, int attemptNo) {
        ReviewExecutionAttempt attempt = new ReviewExecutionAttempt();
        attempt.setId(id);
        attempt.setTaskId(taskId);
        attempt.setAttemptNo(attemptNo);
        attempt.setStatus("COMPLETED");
        return attempt;
    }
}
