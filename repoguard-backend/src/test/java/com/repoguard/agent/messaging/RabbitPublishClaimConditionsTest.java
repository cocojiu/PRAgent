package com.repoguard.agent.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.entity.ReviewTask;
import java.time.LocalDateTime;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RabbitPublishClaimConditionsTest {

    private final LocalDateTime expiredBefore = LocalDateTime.parse("2026-07-07T10:00:00");

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), NotificationEvent.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ReviewTask.class);
    }

    @Test
    void availableColumnBuildsUnclaimedOrExpiredFence() {
        UpdateWrapper<NotificationEvent> wrapper = new UpdateWrapper<>();

        RabbitPublishClaimConditions
            .<NotificationEvent>availableColumn("publish_claimed_at", expiredBefore)
            .accept(wrapper);

        assertThat(wrapper.getSqlSegment())
            .contains("publish_claimed_at IS NULL")
            .contains("OR")
            .contains("publish_claimed_at <=");
    }

    @Test
    void availableLambdaBuildsUnclaimedOrExpiredFence() {
        LambdaQueryWrapper<NotificationEvent> wrapper = new LambdaQueryWrapper<>();

        RabbitPublishClaimConditions
            .availableLambda(NotificationEvent::getPublishClaimedAt, expiredBefore)
            .accept(wrapper);

        assertThat(wrapper.getSqlSegment())
            .contains("publish_claimed_at IS NULL")
            .contains("OR")
            .contains("publish_claimed_at <=");
    }

    @Test
    void staleQueuedColumnRequiresEitherExpiredClaimOrOldUnclaimedTask() {
        UpdateWrapper<ReviewTask> wrapper = new UpdateWrapper<>();

        RabbitPublishClaimConditions
            .<ReviewTask>staleQueuedColumn("publish_claimed_at", "created_at", expiredBefore)
            .accept(wrapper);

        assertThat(wrapper.getSqlSegment())
            .contains("publish_claimed_at IS NOT NULL")
            .contains("publish_claimed_at <=")
            .contains("publish_claimed_at IS NULL")
            .contains("created_at <=");
    }

    @Test
    void staleQueuedLambdaRequiresEitherExpiredClaimOrOldUnclaimedTask() {
        LambdaQueryWrapper<ReviewTask> wrapper = new LambdaQueryWrapper<>();

        RabbitPublishClaimConditions
            .staleQueuedLambda(ReviewTask::getPublishClaimedAt, ReviewTask::getCreatedAt, expiredBefore)
            .accept(wrapper);

        assertThat(wrapper.getSqlSegment())
            .contains("publish_claimed_at IS NOT NULL")
            .contains("publish_claimed_at <=")
            .contains("publish_claimed_at IS NULL")
            .contains("created_at <=");
    }

    @Test
    void rejectsMissingInputs() {
        assertThatThrownBy(() -> RabbitPublishClaimConditions.availableColumn(null, expiredBefore))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("claimedAtColumn");
        assertThatThrownBy(() -> RabbitPublishClaimConditions.availableColumn("publish_claimed_at", null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("expiredBefore");
    }
}
