package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.mapper.NotificationEventMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NotificationPublishEventStateUpdaterTest {

    private final NotificationEventMapper eventMapper =
        org.mockito.Mockito.mock(NotificationEventMapper.class);
    private final NotificationPublishEventStateUpdater updater =
        new NotificationPublishEventStateUpdater(eventMapper);

    @Test
    void marksEventPublishedWithoutOverwritingDeliveredEvent() {
        NotificationEvent event = event();

        updater.markPublished(event);

        UpdateWrapper<NotificationEvent> wrapper = captureUpdateWrapper();
        assertThat(wrapper.getSqlSet()).contains("status", "last_error", "updated_at");
        assertThat(wrapper.getParamNameValuePairs())
            .containsValue(NotificationEventStatus.PUBLISHED.code());
    }

    @Test
    void marksEventPublishFailedWithFailureDecision() {
        NotificationEvent event = event();
        NotificationPublishFailureDecision decision = new NotificationPublishFailureDecision(
            NotificationEventStatus.PUBLISH_FAILED.code(),
            2,
            LocalDateTime.of(2026, 6, 19, 0, 45),
            "confirm timed out"
        );

        updater.markPublishFailed(event, decision);

        UpdateWrapper<NotificationEvent> wrapper = captureUpdateWrapper();
        assertThat(wrapper.getSqlSet()).contains("status", "retry_count", "next_retry_at", "last_error", "updated_at");
        assertThat(wrapper.getParamNameValuePairs())
            .containsValue(NotificationEventStatus.PUBLISH_FAILED.code())
            .containsValue(2)
            .containsValue(LocalDateTime.of(2026, 6, 19, 0, 45))
            .containsValue("confirm timed out");
    }

    private UpdateWrapper<NotificationEvent> captureUpdateWrapper() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<NotificationEvent>> wrapperCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(eventMapper).update(wrapperCaptor.capture());
        return wrapperCaptor.getValue();
    }

    private NotificationEvent event() {
        NotificationEvent event = new NotificationEvent();
        event.setId(99L);
        return event;
    }
}
