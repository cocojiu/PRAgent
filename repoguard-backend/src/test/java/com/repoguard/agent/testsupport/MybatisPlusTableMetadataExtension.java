package com.repoguard.agent.testsupport;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.repoguard.agent.entity.AdminOperationAudit;
import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.DataRetentionCleanupAudit;
import com.repoguard.agent.entity.DataRetentionCleanupLease;
import com.repoguard.agent.entity.GithubCommentPublication;
import com.repoguard.agent.entity.GithubCommentPublicationBatch;
import com.repoguard.agent.entity.GithubCommentPublicationBatchItem;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.entity.NotificationDeliveryLog;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.entity.ReviewRepositoryDimension;
import com.repoguard.agent.entity.ReviewRuleConfig;
import com.repoguard.agent.entity.ReviewRulePolicySnapshot;
import com.repoguard.agent.entity.ReviewStrategyPolicySnapshot;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTaskArchiveSummary;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.entity.SystemSettingLog;
import com.repoguard.agent.entity.SystemSettingsConfig;
import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.entity.UserLoginAudit;
import com.repoguard.agent.entity.UserOperationAudit;
import com.repoguard.agent.entity.UserRefreshToken;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Mirrors the table metadata initialization performed by MyBatis mapper bootstrap.
 * Mockito-only unit tests do not create a MyBatis application context, but production
 * services still build lambda wrappers while those tests exercise their behavior.
 */
public final class MybatisPlusTableMetadataExtension implements BeforeAllCallback {

    private static final Class<?>[] ENTITY_TYPES = {
        AdminOperationAudit.class,
        ChangedFile.class,
        DataRetentionCleanupAudit.class,
        DataRetentionCleanupLease.class,
        GithubCommentPublication.class,
        GithubCommentPublicationBatch.class,
        GithubCommentPublicationBatchItem.class,
        IntegrationConfig.class,
        NotificationChannelBinding.class,
        NotificationDeliveryLog.class,
        NotificationEvent.class,
        ReviewFinding.class,
        ReviewPolicyConfig.class,
        ReviewRepositoryDimension.class,
        ReviewRuleConfig.class,
        ReviewRulePolicySnapshot.class,
        ReviewStrategyPolicySnapshot.class,
        ReviewTask.class,
        ReviewTaskArchiveSummary.class,
        ReviewTimeline.class,
        SystemSettingLog.class,
        SystemSettingsConfig.class,
        UserAccount.class,
        UserLoginAudit.class,
        UserOperationAudit.class,
        UserRefreshToken.class
    };

    private static boolean initialized;

    @Override
    public void beforeAll(ExtensionContext context) {
        initializeOnce();
    }

    private static synchronized void initializeOnce() {
        if (initialized) {
            return;
        }
        MybatisConfiguration configuration = new MybatisConfiguration();
        for (Class<?> entityType : ENTITY_TYPES) {
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, entityType.getName());
            TableInfoHelper.initTableInfo(assistant, entityType);
        }
        initialized = true;
    }
}
