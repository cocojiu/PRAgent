package com.repoguard.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repoguard.agent.common.GlobalExceptionHandler;
import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.dto.NotificationBindingDto;
import com.repoguard.agent.dto.NotificationBindingRequest;
import com.repoguard.agent.dto.NotificationDeliveryDto;
import com.repoguard.agent.dto.NotificationDeliverySummaryDto;
import com.repoguard.agent.dto.NotificationEventDto;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.service.NotificationIntegrationService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

class NotificationIntegrationControllerTest {

    private final RecordingNotificationIntegrationService service = new RecordingNotificationIntegrationService();
    private final MockMvc mockMvc = MockMvcBuilders
        .standaloneSetup(validated(new NotificationIntegrationController(service)))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();

    @SuppressWarnings("unchecked")
    private <T> T validated(T controller) {
        MethodValidationPostProcessor processor = new MethodValidationPostProcessor();
        processor.setProxyTargetClass(true);
        processor.afterPropertiesSet();
        return (T) processor.postProcessAfterInitialization(controller, controller.getClass().getName());
    }

    @Test
    void listBindingsKeepsNotificationBindingContract() throws Exception {
        mockMvc.perform(get("/api/v1/config/notification-bindings")
                .param("page", "2")
                .param("pageSize", "10")
                .param("organization", "repo-guard-demo")
                .param("repository", "spring-boot-demo")
                .param("provider", "feishu"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.message").value("OK"))
            .andExpect(jsonPath("$.timestamp").exists())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items", hasSize(1)))
            .andExpect(jsonPath("$.data.items[0].id").value(1001))
            .andExpect(jsonPath("$.data.items[0].name").value("研发群"))
            .andExpect(jsonPath("$.data.items[0].provider").value("feishu"))
            .andExpect(jsonPath("$.data.items[0].organization").value("repo-guard-demo"))
            .andExpect(jsonPath("$.data.items[0].repository").value("spring-boot-demo"))
            .andExpect(jsonPath("$.data.items[0].enabled").value(true))
            .andExpect(jsonPath("$.data.items[0].webhookUrl").value("https://open.feishu.cn/webhook/xxx"))
            .andExpect(jsonPath("$.data.items[0].secret").value("******"))
            .andExpect(jsonPath("$.data.items[0].notifyReviewCompleted").value(true))
            .andExpect(jsonPath("$.data.items[0].notifyReviewFailed").value(true))
            .andExpect(jsonPath("$.data.items[0].notifyHumanReviewRequired").value(true))
            .andExpect(jsonPath("$.data.items[0].notifyGithubComment").value(false))
            .andExpect(jsonPath("$.data.items[0].status").value("healthy"))
            .andExpect(jsonPath("$.data.items[0].lastCheckedAt").value("2026-06-22 18:00:00"))
            .andExpect(jsonPath("$.data.items[0].createdAt").value("2026-06-20 10:00:00"))
            .andExpect(jsonPath("$.data.items[0].updatedAt").value("2026-06-22 18:00:00"));

        assertThat(service.lastBindingPage).isEqualTo(2);
        assertThat(service.lastBindingPageSize).isEqualTo(10);
        assertThat(service.lastBindingOrganization).isEqualTo("repo-guard-demo");
        assertThat(service.lastBindingRepository).isEqualTo("spring-boot-demo");
        assertThat(service.lastBindingProvider).isEqualTo("feishu");
    }

    @Test
    void listBindingsRejectsOverlongProvider() throws Exception {
        mockMvc.perform(get("/api/v1/config/notification-bindings")
                .param("provider", "x".repeat(33)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void listEventsKeepsNotificationEventContract() throws Exception {
        mockMvc.perform(get("/api/v1/notification-events")
                .param("page", "3")
                .param("pageSize", "5")
                .param("status", "failed")
                .param("taskId", "512"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items", hasSize(1)))
            .andExpect(jsonPath("$.data.items[0].id").value(2001))
            .andExpect(jsonPath("$.data.items[0].eventKey").value("review-failed-512"))
            .andExpect(jsonPath("$.data.items[0].eventType").value("review_failed"))
            .andExpect(jsonPath("$.data.items[0].taskId").value(512))
            .andExpect(jsonPath("$.data.items[0].batchId").value(3001))
            .andExpect(jsonPath("$.data.items[0].status").value("failed"))
            .andExpect(jsonPath("$.data.items[0].retryCount").value(2))
            .andExpect(jsonPath("$.data.items[0].nextRetryAt").value("2026-06-22 18:05:00"))
            .andExpect(jsonPath("$.data.items[0].lastError").value("Feishu webhook timeout"))
            .andExpect(jsonPath("$.data.items[0].deliverySummary.providers[0]").value("feishu"))
            .andExpect(jsonPath("$.data.items[0].deliverySummary.deliveryCount").value(2))
            .andExpect(jsonPath("$.data.items[0].deliverySummary.failedDeliveryCount").value(1))
            .andExpect(jsonPath("$.data.items[0].deliverySummary.latestDeliveryStatus").value("failed"))
            .andExpect(jsonPath("$.data.items[0].createdAt").value("2026-06-22 18:00:00"))
            .andExpect(jsonPath("$.data.items[0].updatedAt").value("2026-06-22 18:01:00"));

        assertThat(service.lastEventPage).isEqualTo(3);
        assertThat(service.lastEventPageSize).isEqualTo(5);
        assertThat(service.lastEventStatus).isEqualTo("failed");
        assertThat(service.lastEventTaskId).isEqualTo(512L);
    }

    @Test
    void listEventsRejectsOverlongStatus() throws Exception {
        mockMvc.perform(get("/api/v1/notification-events")
                .param("status", "x".repeat(33)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void retryEventKeepsNotificationEventContract() throws Exception {
        mockMvc.perform(post("/api/v1/notification-events/2001/retry"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(2001))
            .andExpect(jsonPath("$.data.status").value("queued"))
            .andExpect(jsonPath("$.data.retryCount").value(3));

        assertThat(service.lastRetryEventId).isEqualTo(2001L);
    }

    @Test
    void listDeliveriesKeepsNotificationDeliveryContract() throws Exception {
        mockMvc.perform(get("/api/v1/notification-deliveries")
                .param("page", "4")
                .param("pageSize", "15")
                .param("status", "failed")
                .param("taskId", "512"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items", hasSize(1)))
            .andExpect(jsonPath("$.data.items[0].id").value(4001))
            .andExpect(jsonPath("$.data.items[0].eventId").value(2001))
            .andExpect(jsonPath("$.data.items[0].bindingId").value(1001))
            .andExpect(jsonPath("$.data.items[0].taskId").value(512))
            .andExpect(jsonPath("$.data.items[0].provider").value("feishu"))
            .andExpect(jsonPath("$.data.items[0].status").value("failed"))
            .andExpect(jsonPath("$.data.items[0].attemptCount").value(2))
            .andExpect(jsonPath("$.data.items[0].failureReason").value("Feishu webhook timeout"))
            .andExpect(jsonPath("$.data.items[0].requestId").value("req-001"))
            .andExpect(jsonPath("$.data.items[0].sentAt").value("2026-06-22 18:01:00"))
            .andExpect(jsonPath("$.data.items[0].createdAt").value("2026-06-22 18:00:30"));

        assertThat(service.lastDeliveryPage).isEqualTo(4);
        assertThat(service.lastDeliveryPageSize).isEqualTo(15);
        assertThat(service.lastDeliveryStatus).isEqualTo("failed");
        assertThat(service.lastDeliveryTaskId).isEqualTo(512L);
    }

    @Test
    void listDeliveriesRejectsOverlongStatus() throws Exception {
        mockMvc.perform(get("/api/v1/notification-deliveries")
                .param("status", "x".repeat(33)))
            .andExpect(status().isBadRequest());
    }

    private static final class RecordingNotificationIntegrationService implements NotificationIntegrationService {

        private int lastBindingPage;
        private int lastBindingPageSize;
        private String lastBindingOrganization;
        private String lastBindingRepository;
        private String lastBindingProvider;
        private int lastEventPage;
        private int lastEventPageSize;
        private String lastEventStatus;
        private Long lastEventTaskId;
        private Long lastRetryEventId;
        private int lastDeliveryPage;
        private int lastDeliveryPageSize;
        private String lastDeliveryStatus;
        private Long lastDeliveryTaskId;

        @Override
        public PageResponse<NotificationBindingDto> listBindings(
            int page,
            int pageSize,
            String organization,
            String repository,
            String provider
        ) {
            this.lastBindingPage = page;
            this.lastBindingPageSize = pageSize;
            this.lastBindingOrganization = organization;
            this.lastBindingRepository = repository;
            this.lastBindingProvider = provider;
            return new PageResponse<>(List.of(new NotificationBindingDto(
                1001L,
                "研发群",
                "feishu",
                "repo-guard-demo",
                "spring-boot-demo",
                true,
                "https://open.feishu.cn/webhook/xxx",
                "******",
                true,
                true,
                true,
                false,
                "healthy",
                "2026-06-22 18:00:00",
                null,
                "2026-06-20 10:00:00",
                "2026-06-22 18:00:00",
                "configured",
                "configured"
            )), 1);
        }

        @Override
        public NotificationBindingDto createBinding(NotificationBindingRequest request) {
            throw new UnsupportedOperationException("not used by this contract test");
        }

        @Override
        public NotificationBindingDto updateBinding(Long id, NotificationBindingRequest request) {
            throw new UnsupportedOperationException("not used by this contract test");
        }

        @Override
        public NotificationBindingDto updateBindingStatus(Long id, Boolean enabled) {
            throw new UnsupportedOperationException("not used by this contract test");
        }

        @Override
        public void deleteBinding(Long id) {
            throw new UnsupportedOperationException("not used by this contract test");
        }

        @Override
        public ConnectionTestResultDto testBinding(Long id) {
            throw new UnsupportedOperationException("not used by this contract test");
        }

        @Override
        public PageResponse<NotificationEventDto> listEvents(int page, int pageSize, String status, Long taskId) {
            this.lastEventPage = page;
            this.lastEventPageSize = pageSize;
            this.lastEventStatus = status;
            this.lastEventTaskId = taskId;
            return new PageResponse<>(List.of(notificationEvent(2001L, "failed", 2)), 1);
        }

        @Override
        public NotificationEventDto retryEvent(Long id) {
            this.lastRetryEventId = id;
            return notificationEvent(id, "queued", 3);
        }

        @Override
        public PageResponse<NotificationDeliveryDto> listDeliveries(int page, int pageSize, String status, Long taskId) {
            this.lastDeliveryPage = page;
            this.lastDeliveryPageSize = pageSize;
            this.lastDeliveryStatus = status;
            this.lastDeliveryTaskId = taskId;
            return new PageResponse<>(List.of(new NotificationDeliveryDto(
                4001L,
                2001L,
                1001L,
                512L,
                "feishu",
                "failed",
                2,
                "Feishu webhook timeout",
                "req-001",
                "2026-06-22 18:01:00",
                "2026-06-22 18:00:30"
            )), 1);
        }

        private NotificationEventDto notificationEvent(Long id, String status, int retryCount) {
            return new NotificationEventDto(
                id,
                "review-failed-512",
                "review_failed",
                512L,
                3001L,
                status,
                retryCount,
                "2026-06-22 18:05:00",
                "Feishu webhook timeout",
                new NotificationDeliverySummaryDto(List.of("feishu"), 2, 1, "failed"),
                "2026-06-22 18:00:00",
                "2026-06-22 18:01:00"
            );
        }
    }
}
