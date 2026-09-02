package com.repoguard.agent.notification.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.mapper.NotificationChannelBindingMapper;
import com.repoguard.agent.notification.NotificationEventType;
import com.repoguard.agent.notification.NotificationMessage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NotificationCandidateBindingQueryTest {

    private final NotificationChannelBindingMapper bindingMapper = org.mockito.Mockito.mock(NotificationChannelBindingMapper.class);
    private final NotificationCandidateBindingQuery query = new NotificationCandidateBindingQuery(bindingMapper);

    @Test
    void loadsEnabledBindingsForMessageRepository() {
        NotificationChannelBinding binding = new NotificationChannelBinding();
        binding.setId(7L);
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding));

        List<NotificationChannelBinding> bindings = query.load(message());

        assertThat(bindings).containsExactly(binding);

        ArgumentCaptor<QueryWrapper<NotificationChannelBinding>> wrapperCaptor =
            ArgumentCaptor.captor();
        verify(bindingMapper).selectList(wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment())
            .contains("enabled")
            .contains("organization")
            .contains("repository");
    }

    @Test
    void loadsAllEnabledTenantBindingsForModelReleaseAlerts() {
        NotificationChannelBinding binding = new NotificationChannelBinding();
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding));

        assertThat(query.load(new NotificationMessage(
            NotificationEventType.MODEL_RELEASE_ALERT.code(), null, null, "*", "*", null,
            "LLM 模型发布运行告警", "ALERT", "HIGH", 12, 0, 0, 0, "/release-center", "summary"
        ))).containsExactly(binding);

        ArgumentCaptor<QueryWrapper<NotificationChannelBinding>> wrapperCaptor = ArgumentCaptor.captor();
        verify(bindingMapper).selectList(wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment())
            .contains("enabled")
            .doesNotContain("organization")
            .doesNotContain("repository");
    }

    private NotificationMessage message() {
        return new NotificationMessage(
            NotificationEventType.REVIEW_COMPLETED.code(),
            42L,
            null,
            "octocat",
            "Hello-World",
            7,
            "Improve review flow",
            "COMPLETED",
            "LOW",
            1,
            0,
            0,
            0,
            "/repoguard/tasks/42"
        );
    }
}
