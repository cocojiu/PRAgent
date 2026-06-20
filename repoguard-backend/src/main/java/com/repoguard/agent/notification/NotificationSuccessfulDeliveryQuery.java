package com.repoguard.agent.notification;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.repoguard.agent.entity.NotificationDeliveryLog;
import com.repoguard.agent.mapper.NotificationDeliveryLogMapper;
import org.springframework.stereotype.Component;

@Component
class NotificationSuccessfulDeliveryQuery {

    private final NotificationDeliveryLogMapper deliveryLogMapper;

    NotificationSuccessfulDeliveryQuery(NotificationDeliveryLogMapper deliveryLogMapper) {
        this.deliveryLogMapper = deliveryLogMapper;
    }

    boolean exists(Long eventId, Long bindingId) {
        return deliveryLogMapper.selectCount(
            new QueryWrapper<NotificationDeliveryLog>()
                .eq("event_id", eventId)
                .eq("binding_id", bindingId)
                .eq("status", NotificationDeliveryStatus.SUCCESS.code())
        ) > 0;
    }
}
