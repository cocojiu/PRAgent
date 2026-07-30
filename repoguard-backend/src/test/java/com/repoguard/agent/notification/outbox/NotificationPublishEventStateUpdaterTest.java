package com.repoguard.agent.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.mapper.NotificationEventMapper;
import com.repoguard.agent.notification.NotificationEventStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NotificationPublishEventStateUpdaterTest {

    private final NotificationEventMapper eventMapper =
        org.mockito.Mockito.mock(NotificationEventMapper.class);
    private final NotificationPublishEventStateUpdater updater =
        new NotificationPublishEventStateUpdater(eventMapper);

    @Test
    void marksEventPublishedOnlyWhilePublishLeaseIsOwned() {
        NotificationEvent event = event();
        when(eventMapper.update(any())).thenReturn(1);

        assertThat(updater.markPublished(event)).isTrue();

        UpdateWrapper<NotificationEvent> wrapper = captureUpdateWrapper();
        assertThat(wrapper.getSqlSegment()).contains("status", "publish_claimed_at", "publish_claimed_by");
        assertThat(wrapper.getSqlSet()).contains("status", "next_retry_at", "last_error", "updated_at");
        assertThat(wrapper.getParamNameValuePairs())
            .containsValue(NotificationEventStatus.PUBLISHING.code())
            .containsValue(NotificationEventStatus.PUBLISHED.code());
        assertThat(event.getStatus()).isEqualTo(NotificationEventStatus.PUBLISHED.code());
        assertThat(event.getPublishClaimedAt()).isNull();
        assertThat(event.getPublishClaimedBy()).isNull();
    }

    @Test
    void marksEventPublishFailedWithFailureDecision() {
        NotificationEvent event = event();
        when(eventMapper.update(any())).thenReturn(1);
        NotificationPublishFailureDecision decision = new NotificationPublishFailureDecision(
            NotificationEventStatus.PUBLISH_FAILED.code(),
            2,
            LocalDateTime.of(2026, 6, 19, 0, 45),
            "confirm timed out"
        );

        assertThat(updater.markPublishFailed(event, decision)).isTrue();

        UpdateWrapper<NotificationEvent> wrapper = captureUpdateWrapper();
        assertThat(wrapper.getSqlSet()).contains("status", "retry_count", "next_retry_at", "last_error", "updated_at");
        assertThat(wrapper.getParamNameValuePairs())
            .containsValue(NotificationEventStatus.PUBLISH_FAILED.code())
            .containsValue(2)
            .containsValue(LocalDateTime.of(2026, 6, 19, 0, 45))
            .containsValue("confirm timed out");
        assertThat(event.getStatus()).isEqualTo(NotificationEventStatus.PUBLISH_FAILED.code());
        assertThat(event.getRetryCount()).isEqualTo(2);
        assertThat(event.getPublishClaimedAt()).isNull();
        assertThat(event.getPublishClaimedBy()).isNull();
    }

    @Test
    void lostPublishLeaseDoesNotOverwriteConsumerState() {
        NotificationEvent event = event();
        when(eventMapper.update(any())).thenReturn(0);
        event.setStatus(NotificationEventStatus.DELIVERING.code());

        assertThat(updater.markPublished(event)).isFalse();

        assertThat(event.getStatus()).isEqualTo(NotificationEventStatus.DELIVERING.code());
        assertThat(event.getPublishClaimedAt()).isNotNull();
        assertThat(event.getPublishClaimedBy()).isEqualTo("publisher-1");
    }

    private UpdateWrapper<NotificationEvent> captureUpdateWrapper() {
        ArgumentCaptor<UpdateWrapper<NotificationEvent>> wrapperCaptor = ArgumentCaptor.captor();
        verify(eventMapper).update(wrapperCaptor.capture());
        return wrapperCaptor.getValue();
    }

    private NotificationEvent event() {
        NotificationEvent event = new NotificationEvent();
        event.setId(99L);
        event.setStatus(NotificationEventStatus.PUBLISHING.code());
        event.setPublishClaimedAt(LocalDateTime.of(2026, 6, 19, 0, 40));
        event.setPublishClaimedBy("publisher-1");
        return event;
    }
}
