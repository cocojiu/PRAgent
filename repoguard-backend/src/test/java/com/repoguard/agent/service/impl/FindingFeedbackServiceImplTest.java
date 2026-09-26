package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.repoguard.agent.cache.CacheEvictionService;
import com.repoguard.agent.dto.FindingFeedbackRequest;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.ReviewFindingRiskRecalibrator;
import com.repoguard.agent.review.task.ReviewTaskTransitionStore;
import com.repoguard.agent.timeline.ReviewTimelineAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.LocalDateTime;

class FindingFeedbackServiceImplTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "  replacement  "})
    void explicitlyPersistsClearedNotesAndSecondPrecision(String note) {
        ReviewTaskMapper tasks = mock(ReviewTaskMapper.class);
        ReviewFindingMapper findings = mock(ReviewFindingMapper.class);
        ReviewFindingRiskRecalibrator risk = mock(ReviewFindingRiskRecalibrator.class);
        ReviewTask task = new ReviewTask();
        task.setId(1L);
        task.setCreatedAt(LocalDateTime.of(2026, 9, 21, 0, 0));
        ReviewFinding finding = new ReviewFinding();
        finding.setId(2L);
        finding.setTaskId(1L);
        finding.setCategory("FINDING");
        finding.setCurrentAttempt(true);
        finding.setFeedbackNote("previous note");
        when(tasks.selectById(1L)).thenReturn(task);
        when(findings.selectById(2L)).thenReturn(finding);
        when(risk.recalculate(1L)).thenReturn(new ReviewFindingRiskRecalibrator.Outcome("LOW", false));
        var service = new FindingFeedbackServiceImpl(tasks, findings, mock(ReviewTimelineAppender.class),
            mock(CacheEvictionService.class), risk, mock(ReviewTaskTransitionStore.class));
        LocalDateTime clockValue = LocalDateTime.of(2026, 9, 21, 1, 2, 3, 900_000_000);
        try (var clock = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            clock.when(LocalDateTime::now).thenReturn(clockValue);
            var response = service.updateFindingFeedback(1L, 2L, new FindingFeedbackRequest("unreviewed", note), "admin");
            assertThat(response.feedbackAt()).isEqualTo("2026-09-21 01:02:03");
            assertThat(finding.getFeedbackAt()).isEqualTo(clockValue.withNano(0));
            assertThat(response.feedbackNote()).isEqualTo(note == null || note.isBlank() ? null : note.trim());
        }
        var update = org.mockito.ArgumentCaptor.<LambdaUpdateWrapper<ReviewFinding>>captor();
        verify(findings).update(isNull(), update.capture());
        var wrapper = update.getValue();
        assertThat(wrapper.getSqlSet()).contains("feedback_note=", "feedback_at=")
            .doesNotContain("message=", "recommendation=");
        assertThat(wrapper.getSqlSegment()).contains("id", "task_id");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(finding.getFeedbackNote(), finding.getFeedbackAt());
        verify(findings, never()).updateById(any(ReviewFinding.class));
    }

    @Test
    void constructorRejectsMissingCacheEvictionService() {
        assertThatThrownBy(() -> new FindingFeedbackServiceImpl(
            org.mockito.Mockito.mock(ReviewTaskMapper.class),
            org.mockito.Mockito.mock(ReviewFindingMapper.class),
            org.mockito.Mockito.mock(ReviewTimelineAppender.class),
            null,
            org.mockito.Mockito.mock(ReviewFindingRiskRecalibrator.class),
            org.mockito.Mockito.mock(ReviewTaskTransitionStore.class)
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("cacheEvictionService");
    }
}
