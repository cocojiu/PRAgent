package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.repoguard.agent.controller.ReviewMemberAssignmentController;
import com.repoguard.agent.notification.workflow.ReviewMemberAssignmentService;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.TenantMembershipMapper;
import com.repoguard.agent.service.ReviewWorkflowService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class ReviewMemberAssignmentConfigurationTest {
    @Test void personalAndWorkerDoNotCreateServiceOrEndpoint() {
        for (var settings : java.util.List.of(Map.<String,Object>of("app.edition", "personal"),
            Map.<String,Object>of("app.edition", "enterprise-experimental", "app.runtime.role", "worker",
                "app.runtime.deployment-mode", "split", "app.runtime.api.instance-count", "0"))) {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", settings));
                context.register(ReviewMemberAssignmentService.class, ReviewMemberAssignmentController.class); context.refresh();
                assertThat(context.getBeansOfType(ReviewMemberAssignmentService.class)).isEmpty();
                assertThat(context.getBeansOfType(ReviewMemberAssignmentController.class)).isEmpty();
            }
        }
    }
    @Test void enterpriseCombinedAndApiCreateTheMemberPickerWithoutGithubOrCodeownersConfiguration() {
        for (var settings : java.util.List.of(Map.<String,Object>of("app.edition", "enterprise-experimental"),
            Map.<String,Object>of("app.edition", "enterprise-experimental", "app.runtime.role", "api",
                "app.runtime.deployment-mode", "split", "app.runtime.api.instance-count", "1"))) {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", settings));
                context.registerBean(ReviewTaskMapper.class, () -> mock(ReviewTaskMapper.class));
                context.registerBean(TenantMembershipMapper.class, () -> mock(TenantMembershipMapper.class));
                context.registerBean(ReviewWorkflowService.class, () -> mock(ReviewWorkflowService.class));
                context.register(ReviewMemberAssignmentService.class, ReviewMemberAssignmentController.class); context.refresh();
                assertThat(context.getBeansOfType(ReviewMemberAssignmentService.class)).hasSize(1);
                assertThat(context.getBeansOfType(ReviewMemberAssignmentController.class)).hasSize(1);
            }
        }
    }
}
