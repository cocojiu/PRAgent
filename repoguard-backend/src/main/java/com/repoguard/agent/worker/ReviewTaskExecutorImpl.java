package com.repoguard.agent.worker;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.messaging.ReviewTaskMessage;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewTaskExecutorImpl implements ReviewTaskExecutor {

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTimelineMapper reviewTimelineMapper;

    public ReviewTaskExecutorImpl(ReviewTaskMapper reviewTaskMapper, ReviewTimelineMapper reviewTimelineMapper) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.reviewTimelineMapper = reviewTimelineMapper;
    }

    @Override
    @Transactional
    public void execute(ReviewTaskMessage message) {
        ReviewTask task = reviewTaskMapper.selectById(message.taskId());
        if (task == null || "COMPLETED".equals(task.getStatus())) {
            return;
        }

        LocalDateTime startedAt = LocalDateTime.now();
        task.setStatus("REVIEWING");
        task.setStartedAt(startedAt);
        reviewTaskMapper.updateById(task);
        appendTimeline(task.getId(), "Review started", startedAt, "CURRENT", 2);

        LocalDateTime finishedAt = LocalDateTime.now();
        task.setStatus("COMPLETED");
        task.setRiskLevel("INFO");
        task.setLlmStatus("COMPLETED");
        task.setFinishedAt(finishedAt);
        task.setDurationSeconds((int) Duration.between(startedAt, finishedAt).toSeconds());
        reviewTaskMapper.updateById(task);
        appendTimeline(task.getId(), "Review completed", finishedAt, "DONE", 3);
    }

    private void appendTimeline(Long taskId, String label, LocalDateTime eventTime, String status, int sortOrder) {
        reviewTimelineMapper.update(
            new UpdateWrapper<ReviewTimeline>()
                .eq("task_id", taskId)
                .eq("status", "CURRENT")
                .set("status", "DONE")
        );

        ReviewTimeline timeline = new ReviewTimeline();
        timeline.setTaskId(taskId);
        timeline.setLabel(label);
        timeline.setEventTime(eventTime);
        timeline.setStatus(status);
        timeline.setSortOrder(sortOrder);
        reviewTimelineMapper.insert(timeline);
    }
}
