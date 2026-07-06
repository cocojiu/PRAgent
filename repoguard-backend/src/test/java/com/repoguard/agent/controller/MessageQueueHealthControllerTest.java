package com.repoguard.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repoguard.agent.dto.ActiveRabbitMqConfigDto;
import com.repoguard.agent.dto.MessageQueueExceptionTaskDto;
import com.repoguard.agent.dto.MessageQueueHealthResponse;
import com.repoguard.agent.dto.MessageQueueMetricDto;
import com.repoguard.agent.dto.MessageQueueRequeueResponse;
import com.repoguard.agent.dto.RabbitMqTopologyDto;
import com.repoguard.agent.dto.RetryCompensationStatusDto;
import com.repoguard.agent.service.MessageQueueHealthService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MessageQueueHealthControllerTest {

    private Long lastRequeueTaskId;

    private final MessageQueueHealthService service = new MessageQueueHealthService() {
        @Override
        public MessageQueueHealthResponse getHealth() {
            return new MessageQueueHealthResponse(
                new ActiveRabbitMqConfigDto(
                    "RABBITMQ",
                    "CONNECTED",
                    "CONNECTED",
                    "amqp://localhost:5672",
                    "repoguard",
                    "/",
                    "2026-06-10 21:02:12",
                    null,
                    "2026-06-10 20:58:00",
                    "cfg-20260610-205800",
                    "Testing a connection does not switch the active configuration; save integration settings to take effect."
                ),
                new RabbitMqTopologyDto(
                    "repoguard.review.exchange.v2",
                    "repoguard.review.queue.v2",
                    "repoguard.review.created.v2",
                    "repoguard.review.dlx",
                    "repoguard.review.dlq",
                    "repoguard.review.dead"
                ),
                List.of(new MessageQueueMetricDto("Publish failed", "1", "Waiting for compensation", "trend danger", "red")),
                new RetryCompensationStatusDto(10, 60000L, 20, 120000L, 1L, null, "publisher confirm timed out"),
                List.of(new MessageQueueExceptionTaskDto(
                    42L,
                    "cocojiu",
                    "PRAgent",
                    128,
                    "PUBLISH_FAILED",
                    2,
                    "2026-06-10 21:08:00",
                    "repoguard-a1",
                    "2026-06-10 21:02:00",
                    "publisher confirm timed out"
                )),
                "2026-06-10 21:03:00",
                "DATABASE_TASK_STATE"
            );
        }

        @Override
        public MessageQueueRequeueResponse requeueTask(Long taskId) {
            lastRequeueTaskId = taskId;
            return new MessageQueueRequeueResponse(taskId, "queued", "Message task requeued", 0);
        }
    };

    private final MockMvc mockMvc = MockMvcBuilders
        .standaloneSetup(new MessageQueueHealthController(service))
        .build();

    @Test
    void getHealthReturnsMessageQueueHealthData() throws Exception {
        mockMvc.perform(get("/api/v1/message-queue/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.message").value("OK"))
            .andExpect(jsonPath("$.timestamp").exists())
            .andExpect(jsonPath("$.data.activeConfig.provider").value("RABBITMQ"))
            .andExpect(jsonPath("$.data.activeConfig.status").value("CONNECTED"))
            .andExpect(jsonPath("$.data.activeConfig.runtimeConnectionStatus").value("CONNECTED"))
            .andExpect(jsonPath("$.data.activeConfig.baseUrl").value("amqp://localhost:5672"))
            .andExpect(jsonPath("$.data.activeConfig.virtualHost").value("/"))
            .andExpect(jsonPath("$.data.activeConfig.configVersion").value("cfg-20260610-205800"))
            .andExpect(jsonPath("$.data.activeConfig.switchNotice").value("Testing a connection does not switch the active configuration; save integration settings to take effect."))
            .andExpect(jsonPath("$.data.topology.exchange").value("repoguard.review.exchange.v2"))
            .andExpect(jsonPath("$.data.topology.queue").value("repoguard.review.queue.v2"))
            .andExpect(jsonPath("$.data.topology.routingKey").value("repoguard.review.created.v2"))
            .andExpect(jsonPath("$.data.topology.deadLetterQueue").value("repoguard.review.dlq"))
            .andExpect(jsonPath("$.data.metrics", hasSize(1)))
            .andExpect(jsonPath("$.data.metrics[0].label").value("Publish failed"))
            .andExpect(jsonPath("$.data.metrics[0].value").value("1"))
            .andExpect(jsonPath("$.data.retryCompensation.maxAttempts").value(10))
            .andExpect(jsonPath("$.data.retryCompensation.batchSize").value(20))
            .andExpect(jsonPath("$.data.retryCompensation.claimedTaskCount").value(1))
            .andExpect(jsonPath("$.data.retryCompensation.latestFailureReason").value("publisher confirm timed out"))
            .andExpect(jsonPath("$.data.exceptionTasks[0].taskId").value(42))
            .andExpect(jsonPath("$.data.exceptionTasks[0].organization").value("cocojiu"))
            .andExpect(jsonPath("$.data.exceptionTasks[0].repository").value("PRAgent"))
            .andExpect(jsonPath("$.data.exceptionTasks[0].prNumber").value(128))
            .andExpect(jsonPath("$.data.exceptionTasks[0].status").value("PUBLISH_FAILED"))
            .andExpect(jsonPath("$.data.generatedAt").value("2026-06-10 21:03:00"))
            .andExpect(jsonPath("$.data.dataSource").value("DATABASE_TASK_STATE"));
    }

    @Test
    void requeueTaskReturnsRequeueResult() throws Exception {
        mockMvc.perform(post("/api/v1/message-queue/tasks/42/requeue"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.message").value("OK"))
            .andExpect(jsonPath("$.timestamp").exists())
            .andExpect(jsonPath("$.data.taskId").value(42))
            .andExpect(jsonPath("$.data.status").value("queued"))
            .andExpect(jsonPath("$.data.message").value("Message task requeued"))
            .andExpect(jsonPath("$.data.publishAttempts").value(0));
        assertThat(lastRequeueTaskId).isEqualTo(42L);
    }
}
