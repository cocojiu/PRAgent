package com.repoguard.agent.review.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.repoguard.agent.authentication.AuthenticatedPrincipal;
import com.repoguard.agent.authentication.RequestAuthenticationAttributes;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.controller.ReviewProgressController;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.security.AuthAccountCache;
import com.repoguard.agent.tenancy.TenantContext;
import com.repoguard.agent.tenancy.TenantProperties;
import com.repoguard.agent.tenancy.TenantResolutionService;
import java.time.Instant;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ReviewProgressStreamServiceTest {
    private final ReviewTaskMapper tasks = mock(ReviewTaskMapper.class);
    private final ReviewTimelineMapper timelines = mock(ReviewTimelineMapper.class);
    private final AuthAccountCache accounts = mock(AuthAccountCache.class);
    private final TenantProperties properties = new TenantProperties();
    private final TenantResolutionService tenants = mock(TenantResolutionService.class);
    private ReviewProgressStreamService streams;
    private final AuthenticatedPrincipal principal = new AuthenticatedPrincipal(7L, "reader", "USER",
        Instant.now().plusSeconds(600).getEpochSecond(), 2);

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ReviewTask.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ReviewTimeline.class);
        UserAccount account = new UserAccount();
        account.setStatus("ACTIVE");
        account.setSessionVersion(2);
        when(accounts.findById(7L)).thenReturn(account);
        ReviewTask task = new ReviewTask();
        task.setId(9L);
        task.setStatus("QUEUED");
        when(tasks.selectOne(any())).thenReturn(task);
        streams = new ReviewProgressStreamService(tasks, timelines, accounts, properties, tenants);
    }

    @AfterEach
    void cleanup() {
        streams.close();
    }

    @Test
    void rejectsExpiredSessionAndAdminKeyIdentity() {
        assertThatThrownBy(() -> streams.open(9L, new AuthenticatedPrincipal(7L, "reader", "USER", 1), null))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> streams.open(9L, new AuthenticatedPrincipal(0L, "key", "ADMIN", Long.MAX_VALUE), null))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsRevokedSessionsAndMissingTasksBeforeOpening() {
        when(accounts.findById(7L)).thenReturn(null);
        assertThatThrownBy(() -> streams.open(9L, principal, null)).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsMissingTask() {
        when(tasks.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> streams.open(9L, principal, null)).isInstanceOf(BusinessException.class);
    }

    @Test
    void closesWithoutSendingDataWhenSessionIsRevokedAfterConnect() throws Exception {
        UserAccount active = new UserAccount();
        active.setStatus("ACTIVE");
        active.setSessionVersion(2);
        when(accounts.findById(7L)).thenReturn(active).thenReturn(null);
        var mvc = MockMvcBuilders.standaloneSetup(new ReviewProgressController(streams)).build();
        var pending = mvc.perform(get("/api/v1/reviews/9/events")
            .requestAttr(RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL, principal)).andReturn();
        pending.getAsyncResult(3000);
        var result = mvc.perform(asyncDispatch(pending)).andReturn();
        assertThat(result.getResponse().getContentAsString()).isEmpty();
        verify(timelines, never()).selectOne(any());
    }

    @Test
    void rejectsTenantRevocationBeforeReadingTask() {
        properties.setEnabled(true);
        when(tenants.resolve(7L, 1L, null)).thenThrow(new BusinessException(
            com.repoguard.agent.common.ErrorCode.FORBIDDEN, "revoked"));
        assertThatThrownBy(() -> streams.open(9L, principal, null)).hasMessage("revoked");
        verify(tasks, never()).selectOne(any());
    }

    @Test
    void boundsConnectionsPerUser() {
        streams.open(9L, principal, null);
        streams.open(10L, principal, null);
        assertThatThrownBy(() -> streams.open(11L, principal, null)).hasMessageContaining("limit");
    }

    @Test
    void resynchronizesFutureCursorAndOnlySendsSafeInvalidationInCapturedTenant() throws Exception {
        when(tasks.selectOne(any())).thenAnswer(invocation -> {
            assertThat(TenantContext.currentTenantId()).isEqualTo(11L);
            ReviewTask task = new ReviewTask();
            task.setId(9L);
            task.setStatus("COMPLETED");
            task.setLlmPromptSummary("private prompt must not stream");
            return task;
        });
        ReviewTimeline timeline = new ReviewTimeline();
        timeline.setId(42L);
        timeline.setLabel("provider private message");
        when(timelines.selectOne(any())).thenReturn(timeline);
        var mvc = MockMvcBuilders.standaloneSetup(new ReviewProgressController(streams)).build();
        org.springframework.test.web.servlet.MvcResult pending;
        try (var _ = TenantContext.withTenant(11L)) {
            pending = mvc.perform(get("/api/v1/reviews/9/events").header("Last-Event-ID", "999")
                .requestAttr(RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL, principal)).andReturn();
        }
        pending.getAsyncResult(3000);
        var result = mvc.perform(asyncDispatch(pending)).andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("event:progress", "id:42", "taskId")
            .doesNotContain("private", "provider");
        assertThat(result.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
        assertThat(TenantContext.currentTenantId()).isNull();
    }
}
