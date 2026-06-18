package com.repoguard.agent.notification;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.mapper.NotificationChannelBindingMapper;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class NotificationCandidateBindingQuery {

    private final NotificationChannelBindingMapper bindingMapper;

    NotificationCandidateBindingQuery(NotificationChannelBindingMapper bindingMapper) {
        this.bindingMapper = bindingMapper;
    }

    List<NotificationChannelBinding> load(NotificationMessage message) {
        return bindingMapper.selectList(
            new QueryWrapper<NotificationChannelBinding>()
                .eq("enabled", true)
                .eq("organization", message.organization())
                .eq("repository", message.repository())
        );
    }
}
