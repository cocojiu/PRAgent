package com.repoguard.agent.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repoguard.agent.dto.ActiveRabbitMqConfigDto;
import com.repoguard.agent.dto.MessageQueueExceptionTaskDto;
import com.repoguard.agent.dto.MessageQueueHealthResponse;
import com.repoguard.agent.dto.MessageQueueMetricDto;
import com.repoguard.agent.dto.RabbitMqTopologyDto;
import com.repoguard.agent.dto.RetryCompensationStatusDto;
import com.repoguard.agent.service.MessageQueueHealthService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MessageQueueHealthControllerTest {

    private final MessageQueueHealthService service = () -> new MessageQueueHealthResponse(
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

    private final MockMvc mockMvc = MockMvcBuilders
        .standaloneSetup(new MessageQueueHealthController(service))
        .build();

    @Test
    void getHealthReturnsMessageQueueHealthData() throws Exception {
        mockMvc.perform(get("/api/v1/message-queue/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.activeConfig.provider").value("RABBITMQ"))
            .andExpect(jsonPath("$.data.activeConfig.runtimeConnectionStatus").value("CONNECTED"))
            .andExpect(jsonPath("$.data.activeConfig.configVersion").value("cfg-20260610-205800"))
            .andExpect(jsonPath("$.data.topology.queue").value("repoguard.review.queue.v2"))
            .andExpect(jsonPath("$.data.metrics", hasSize(1)))
            .andExpect(jsonPath("$.data.retryCompensation.claimedTaskCount").value(1))
            .andExpect(jsonPath("$.data.exceptionTasks[0].status").value("PUBLISH_FAILED"));
    }
}
