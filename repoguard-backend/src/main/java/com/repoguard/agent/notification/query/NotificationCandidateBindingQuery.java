package com.repoguard.agent.notification.query;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.mapper.NotificationChannelBindingMapper;
import com.repoguard.agent.notification.NotificationMessage;
import com.repoguard.agent.notification.NotificationEventType;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class NotificationCandidateBindingQuery {

    private final NotificationChannelBindingMapper bindingMapper;

    public NotificationCandidateBindingQuery(NotificationChannelBindingMapper bindingMapper) {
        this.bindingMapper = bindingMapper;
    }

    public List<NotificationChannelBinding> load(NotificationMessage message) {
        QueryWrapper<NotificationChannelBinding> query = new QueryWrapper<NotificationChannelBinding>().eq("enabled", true);
        if (NotificationEventType.from(message.eventType()) != NotificationEventType.MODEL_RELEASE_ALERT) {
            query.eq("organization", message.organization()).eq("repository", message.repository());
        }
        return bindingMapper.selectList(query);
    }
}
