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
    void sameTenantTaskSharesReadsButRevocationRemainsPerSubscriber() {
        streams.close();
        var scheduler = mock(java.util.concurrent.ScheduledThreadPoolExecutor.class);
        java.util.List<Runnable> ticks = new java.util.ArrayList<>();
        java.util.concurrent.ScheduledFuture<?> firstWarmup = mock(java.util.concurrent.ScheduledFuture.class);
        java.util.concurrent.ScheduledFuture<?> secondWarmup = mock(java.util.concurrent.ScheduledFuture.class);
        org.mockito.Mockito.doReturn(firstWarmup, secondWarmup).when(scheduler).schedule(any(Runnable.class),
            org.mockito.ArgumentMatchers.eq(0L), org.mockito.ArgumentMatchers.eq(java.util.concurrent.TimeUnit.MILLISECONDS));
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(), any(java.util.concurrent.TimeUnit.class))).thenAnswer(call -> {
                ticks.add(call.getArgument(0));
                return mock(java.util.concurrent.ScheduledFuture.class);
            });
        streams = new ReviewProgressStreamService(tasks, timelines, accounts, properties, tenants,
            new ReviewProgressStreamService.Limits(), scheduler);
        UserAccount second = new UserAccount(); second.setStatus("ACTIVE"); second.setSessionVersion(2);
        when(accounts.findById(8L)).thenReturn(second);
        var other = new AuthenticatedPrincipal(8L, "other", "USER", principal.expiresAt(), 2);
        streams.open(9L, principal, null);
        streams.open(9L, other, null);
        assertThat(ticks).hasSize(1);
        org.mockito.Mockito.clearInvocations(tasks, timelines, accounts);
        ticks.getFirst().run();
        verify(tasks).selectOne(any());
        verify(timelines).selectOne(any());
        verify(accounts, org.mockito.Mockito.timeout(1000).atLeast(2)).findById(7L);
        verify(accounts, org.mockito.Mockito.timeout(1000).atLeast(2)).findById(8L);
        when(accounts.findById(7L)).thenReturn(null);
        org.mockito.Mockito.clearInvocations(tasks, timelines);
        ticks.getFirst().run();
        org.mockito.Mockito.verifyNoInteractions(tasks, timelines);
        verify(firstWarmup).cancel(false);
        verify(secondWarmup, never()).cancel(false);
        when(accounts.findById(8L)).thenReturn(null);
        org.mockito.Mockito.clearInvocations(tasks, timelines);
        ticks.getFirst().run();
        org.mockito.Mockito.verifyNoInteractions(tasks, timelines);
    }

    @Test
    void separateTenantTasksNeverShareASampleGroup() {
        streams.close();
        var scheduler = mock(java.util.concurrent.ScheduledThreadPoolExecutor.class);
        java.util.List<Runnable> ticks = new java.util.ArrayList<>();
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(), any(java.util.concurrent.TimeUnit.class))).thenAnswer(call -> {
                ticks.add(call.getArgument(0));
                return mock(java.util.concurrent.ScheduledFuture.class);
            });
        streams = new ReviewProgressStreamService(tasks, timelines, accounts, properties, tenants,
            new ReviewProgressStreamService.Limits(), scheduler);
        try (var _ = TenantContext.withTenant(11L)) { streams.open(9L, principal, null); }
        try (var _ = TenantContext.withTenant(12L)) { streams.open(9L, principal, null); }
        assertThat(ticks).hasSize(2);
        java.util.List<Long> observed = new java.util.ArrayList<>();
        when(tasks.selectOne(any())).thenAnswer(call -> {
            observed.add(TenantContext.requireTenantId());
            ReviewTask task = new ReviewTask(); task.setId(9L); task.setStatus("QUEUED"); return task;
        });
        ticks.forEach(Runnable::run);
        assertThat(observed).containsExactly(11L, 12L);
        assertThat(TenantContext.currentTenantId()).isNull();
    }

    @Test
    void slowSubscriberDoesNotBlockSamplingOrDeliveryForAnotherTask() throws Exception {
        streams.close();
        var scheduler = mock(java.util.concurrent.ScheduledThreadPoolExecutor.class);
        java.util.List<Runnable> ticks = new java.util.ArrayList<>();
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(), any(java.util.concurrent.TimeUnit.class))).thenAnswer(call -> {
                ticks.add(call.getArgument(0));
                return mock(java.util.concurrent.ScheduledFuture.class);
            });
        var blocked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var delivered = new java.util.concurrent.CountDownLatch(1);
        var slow = mock(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.class);
        var fast = mock(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.class);
        org.mockito.Mockito.doAnswer(call -> {
            blocked.countDown();
            assertThat(release.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(slow).send(any(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder.class));
        org.mockito.Mockito.doAnswer(call -> { delivered.countDown(); return null; }).when(fast)
            .send(any(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder.class));
        var emitters = new java.util.ArrayDeque<>(java.util.List.of(slow, fast));
        streams = new ReviewProgressStreamService(tasks, timelines, accounts, properties, tenants,
            new ReviewProgressStreamService.Limits(), scheduler) {
            @Override org.springframework.web.servlet.mvc.method.annotation.SseEmitter newEmitter() {
                return emitters.removeFirst();
            }
        };
        try {
            streams.open(9L, principal, null);
            streams.open(10L, principal, null);
            ticks.getFirst().run();
            assertThat(blocked.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            for (int index = 0; index < 100; index++) ticks.getFirst().run();
            ticks.getLast().run();
            assertThat(delivered.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            verify(slow, org.mockito.Mockito.times(1))
                .send(any(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder.class));
        } finally { release.countDown(); }
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
        try (var _ = TenantContext.withTenant(1L)) {
            assertThatThrownBy(() -> streams.open(9L, principal, null)).hasMessage("revoked");
        }
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
