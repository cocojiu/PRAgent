package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.repoguard.agent.entity.NotificationDeliveryLog;
import com.repoguard.agent.mapper.NotificationDeliveryLogMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NotificationSuccessfulDeliveryQueryTest {

    private final NotificationDeliveryLogMapper deliveryLogMapper =
        org.mockito.Mockito.mock(NotificationDeliveryLogMapper.class);
    private final NotificationSuccessfulDeliveryQuery query =
        new NotificationSuccessfulDeliveryQuery(deliveryLogMapper);

    @Test
    void returnsTrueWhenSuccessfulDeliveryAlreadyExists() {
        when(deliveryLogMapper.selectCount(any())).thenReturn(1L);

        boolean exists = query.exists(11L, 7L);

        assertThat(exists).isTrue();
        assertSuccessfulDeliveryQuery();
    }

    @Test
    void returnsFalseWhenSuccessfulDeliveryDoesNotExist() {
        when(deliveryLogMapper.selectCount(any())).thenReturn(0L);

        boolean exists = query.exists(11L, 7L);

        assertThat(exists).isFalse();
        assertSuccessfulDeliveryQuery();
    }

    private void assertSuccessfulDeliveryQuery() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<NotificationDeliveryLog>> wrapperCaptor =
            ArgumentCaptor.forClass(QueryWrapper.class);
        verify(deliveryLogMapper).selectCount(wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment())
            .contains("event_id")
            .contains("binding_id")
            .contains("status");
        assertThat(wrapperCaptor.getValue().getParamNameValuePairs().values())
            .contains(11L, 7L, NotificationDeliveryStatus.SUCCESS.code());
    }
}
