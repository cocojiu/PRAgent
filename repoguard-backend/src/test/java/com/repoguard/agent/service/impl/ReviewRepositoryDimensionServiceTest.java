package com.repoguard.agent.service.impl;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.mapper.ReviewRepositoryDimensionMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewRepositoryDimensionServiceTest {

    private final ReviewRepositoryDimensionMapper mapper =
        org.mockito.Mockito.mock(ReviewRepositoryDimensionMapper.class);
    private final ReviewRepositoryDimensionService service = new ReviewRepositoryDimensionService(mapper);

    @Test
    void recordRepositoryTrimsAndUpsertsValidRepository() {
        LocalDateTime seenAt = LocalDateTime.of(2026, 7, 8, 12, 0);

        service.recordRepository(" codex ", " repo-guard ", seenAt);

        verify(mapper).upsertRepository("codex", "repo-guard", seenAt);
    }

    @Test
    void recordRepositorySkipsBlankValues() {
        service.recordRepository(" ", "repo-guard", null);
        service.recordRepository("codex", "", null);

        verify(mapper, never()).upsertRepository(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(LocalDateTime.class)
        );
    }

    @Test
    void listRepositoryLabelsReturnsDimensionLabels() {
        when(mapper.selectActiveRepositoryLabels())
            .thenReturn(List.of("codex/repo-guard", "openai/repo-guard"));

        org.assertj.core.api.Assertions.assertThat(service.listRepositoryLabels())
            .containsExactly("codex/repo-guard", "openai/repo-guard");
    }
}
