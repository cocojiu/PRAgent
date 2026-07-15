package com.repoguard.agent.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class DataRetentionCandidateQueryTest {

    private final ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final DataRetentionCandidateQuery candidateQuery = new DataRetentionCandidateQuery(
        reviewTaskMapper,
        new ReviewTaskStateMachine()
    );

    @Test
    void selectsCandidateCountAndOldestTaskIds() {
        when(reviewTaskMapper.selectCount(any())).thenReturn(4L);
        when(reviewTaskMapper.selectList(any())).thenReturn(List.of(task(7L), task(9L)));

        DataRetentionCandidateQuery.CandidateSelection selection = candidateQuery.select(
            LocalDateTime.of(2026, 7, 1, 0, 0),
            2
        );

        assertThat(selection.candidateTasks()).isEqualTo(4L);
        assertThat(selection.taskIds()).containsExactly(7L, 9L);
        verify(reviewTaskMapper).selectCount(any());
        verify(reviewTaskMapper).selectList(any());
    }

    @Test
    void returnsAnImmutableTaskSelection() {
        when(reviewTaskMapper.selectCount(any())).thenReturn(1L);
        when(reviewTaskMapper.selectList(any())).thenReturn(List.of(task(7L)));

        DataRetentionCandidateQuery.CandidateSelection selection = candidateQuery.select(LocalDateTime.now(), 1);

        assertThatThrownBy(() -> selection.taskIds().add(8L))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsMissingCutoffBeforeQuerying() {
        assertThatThrownBy(() -> candidateQuery.select(null, 10))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("cutoff");
    }

    private ReviewTask task(Long id) {
        ReviewTask task = new ReviewTask();
        task.setId(id);
        return task;
    }
}
