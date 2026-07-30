package com.repoguard.agent.notification.query;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.repoguard.agent.entity.NotificationDeliveryLog;
import com.repoguard.agent.mapper.NotificationDeliveryLogMapper;
import com.repoguard.agent.notification.delivery.NotificationDeliveryStatus;
import org.springframework.stereotype.Component;

@Component
public class NotificationSuccessfulDeliveryQuery {

    private final NotificationDeliveryLogMapper deliveryLogMapper;

    public NotificationSuccessfulDeliveryQuery(NotificationDeliveryLogMapper deliveryLogMapper) {
        this.deliveryLogMapper = deliveryLogMapper;
    }

    public boolean exists(Long eventId, Long bindingId) {
        return deliveryLogMapper.selectCount(
            new QueryWrapper<NotificationDeliveryLog>()
                .eq("event_id", eventId)
                .eq("binding_id", bindingId)
                .eq("status", NotificationDeliveryStatus.SUCCESS.code())
        ) > 0;
    }
}
