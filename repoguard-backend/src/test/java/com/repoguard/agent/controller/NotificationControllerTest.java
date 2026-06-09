package com.repoguard.agent.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repoguard.agent.dto.NotificationCenterDto;
import com.repoguard.agent.dto.NotificationItemDto;
import com.repoguard.agent.service.NotificationService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class NotificationControllerTest {

    private final NotificationService notificationService = () -> new NotificationCenterDto(
        1,
        "2026-06-09 21:30:00",
        List.of(new NotificationItemDto(
            "review-failed-1",
            "danger",
            "审查任务失败",
            "PRAgent PR #5 执行失败，建议查看失败原因并重试。",
            "刚刚",
            "/repoguard/tasks/1",
            "2026-06-09 21:30:00"
        ))
    );

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new NotificationController(notificationService)).build();

    @Test
    void getNotificationsReturnsNotificationCenter() throws Exception {
        mockMvc.perform(get("/api/v1/notifications"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items", hasSize(1)))
            .andExpect(jsonPath("$.data.items[0].targetPath").value("/repoguard/tasks/1"));
    }
}
