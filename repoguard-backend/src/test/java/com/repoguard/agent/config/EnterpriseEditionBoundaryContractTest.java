package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.cache.ClusterCacheInvalidationPoller;
import com.repoguard.agent.cache.DatabaseClusterCacheInvalidationPublisher;
import com.repoguard.agent.controller.EnterpriseTenantController;
import com.repoguard.agent.controller.EnterpriseTenantQuotaController;
import com.repoguard.agent.controller.MessageQueueHealthController;
import com.repoguard.agent.controller.NotificationController;
import com.repoguard.agent.controller.NotificationIntegrationController;
import com.repoguard.agent.controller.UserManagementController;
import com.repoguard.agent.identity.EnterpriseOidcConfiguration;
import com.repoguard.agent.identity.internal.DefaultEnterpriseOidcAuthenticator;
import com.repoguard.agent.messaging.health.MessageQueueHealthServiceImpl;
import com.repoguard.agent.notification.center.NotificationServiceImpl;
import com.repoguard.agent.notification.facade.NotificationIntegrationServiceImpl;
import com.repoguard.agent.review.task.ReviewTaskRequeueService;
import com.repoguard.agent.tenancy.EnterpriseTenantAdminService;
import com.repoguard.agent.tenancy.TenantQuotaService;
import com.repoguard.agent.user.internal.DefaultUserManagementLifecycle;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;

class EnterpriseEditionBoundaryContractTest {

    @Test
    void enterpriseRuntimeSurfaceIsExplicitlyMarked() throws ClassNotFoundException {
        List<Class<?>> enterpriseTypes = List.of(
            EnterpriseTenantController.class,
            EnterpriseTenantQuotaController.class,
            EnterpriseTenantAdminService.class,
            TenantQuotaService.class,
            UserManagementController.class,
            DefaultUserManagementLifecycle.class,
            MessageQueueHealthController.class,
            MessageQueueHealthServiceImpl.class,
            Class.forName("com.repoguard.agent.messaging.health.MessageQueueHealthQueryService"),
            ReviewTaskRequeueService.class,
            NotificationController.class,
            NotificationIntegrationController.class,
            NotificationServiceImpl.class,
            NotificationIntegrationServiceImpl.class,
            ClusterCacheInvalidationPoller.class,
            DatabaseClusterCacheInvalidationPublisher.class,
            EnterpriseOidcConfiguration.class,
            DefaultEnterpriseOidcAuthenticator.class
        );

        assertThat(enterpriseTypes)
            .allSatisfy(type -> assertThat(AnnotatedElementUtils.hasAnnotation(
                type,
                EnterpriseEditionEnabled.class
            )).as(type.getName()).isTrue());
    }
}
