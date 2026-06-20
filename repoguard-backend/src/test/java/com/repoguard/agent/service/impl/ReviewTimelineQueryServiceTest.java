package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ReviewTimelineQueryServiceTest {

    private final ReviewTimelineMapper reviewTimelineMapper = Mockito.mock(ReviewTimelineMapper.class);
    private final ReviewTimelineQueryService service = new ReviewTimelineQueryService(reviewTimelineMapper);

    @Test
    void loadsTimelineMapByTaskIdAndGroupsResults() {
        ReviewTask first = task(101L);
        ReviewTask second = task(102L);
        when(reviewTimelineMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
            timeline(101L, "Queued", "DONE", 1),
            timeline(102L, "Running", "CURRENT", 2),
            timeline(101L, "Failed", "FAILED", 3)
        ));

        var result = service.loadByTaskId(List.of(first, second));

        assertThat(result).containsOnlyKeys(101L, 102L);
        assertThat(service.labels(result.get(101L))).containsExactly("Queued", "Failed");
        assertThat(service.labels(result.get(102L))).containsExactly("Running");
    }

    @Test
    void loadsTimelineItemsAndMapsDisplayStatus() {
        when(reviewTimelineMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
            timeline(101L, "Queued", "DONE", 1),
            timeline(101L, "Running", "CURRENT", 2),
            timeline(101L, "Failed", "FAILED", 3),
            timeline(101L, "Waiting", "PENDING", 4)
        ));

        var result = service.loadItemsByTaskId(101L);

        assertThat(result).extracting("label").containsExactly("Queued", "Running", "Failed", "Waiting");
        assertThat(result).extracting("status").containsExactly("done", "current", "done", "pending");
        assertThat(service.itemLabels(result)).containsExactly("Queued", "Running", "Failed", "Waiting");
        assertThat(service.latestItem(service.loadByTaskId(101L)).label()).isEqualTo("Waiting");
    }

    @Test
    void returnsEmptyCollectionsForEmptyInput() {
        assertThat(service.loadByTaskId(List.of())).isEmpty();
        assertThat(service.labels(List.of())).isEmpty();
        assertThat(service.itemLabels(List.of())).isEmpty();
        assertThat(service.latestItem(List.of())).isNull();
    }

    private ReviewTask task(Long id) {
        ReviewTask task = new ReviewTask();
        task.setId(id);
        return task;
    }

    private ReviewTimeline timeline(Long taskId, String label, String status, int sortOrder) {
        ReviewTimeline timeline = new ReviewTimeline();
        timeline.setTaskId(taskId);
        timeline.setLabel(label);
        timeline.setStatus(status);
        timeline.setSortOrder(sortOrder);
        timeline.setEventTime(LocalDateTime.of(2026, 6, 19, 10, sortOrder));
        return timeline;
    }
}
