package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.mapper.NotificationChannelBindingMapper;
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
        when(bindingMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(binding));

        List<NotificationChannelBinding> bindings = query.load(message());

        assertThat(bindings).containsExactly(binding);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<NotificationChannelBinding>> wrapperCaptor =
            ArgumentCaptor.forClass(QueryWrapper.class);
        verify(bindingMapper).selectList(wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment())
            .contains("enabled")
            .contains("organization")
            .contains("repository");
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
