package com.repoguard.agent.notification.query;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.mapper.NotificationChannelBindingMapper;
import com.repoguard.agent.notification.NotificationMessage;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class NotificationCandidateBindingQuery {

    private final NotificationChannelBindingMapper bindingMapper;

    public NotificationCandidateBindingQuery(NotificationChannelBindingMapper bindingMapper) {
        this.bindingMapper = bindingMapper;
    }

    public List<NotificationChannelBinding> load(NotificationMessage message) {
        return bindingMapper.selectList(
            new QueryWrapper<NotificationChannelBinding>()
                .eq("enabled", true)
                .eq("organization", message.organization())
                .eq("repository", message.repository())
        );
    }
}
