package com.repoguard.agent.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.repoguard.agent.tenancy.PlatformTenantScope;
import com.repoguard.agent.tenancy.TenantContext;
import com.repoguard.agent.tenancy.TenantProperties;
import java.util.Locale;
import java.util.Set;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisPlusConfig {

    private static final Set<String> TENANT_TABLES = Set.of(
        "changed_file",
        "dashboard_daily_snapshot_refresh_state",
        "dashboard_llm_quality_daily_stat",
        "dashboard_review_daily_stat",
        "dashboard_rule_daily_stat",
        "github_comment_publication",
        "github_comment_publication_batch",
        "github_comment_publication_batch_item",
        "github_check_run",
        "github_check_run_policy",
        "integration_config",
        "notification_channel_binding",
        "notification_delivery_log",
        "notification_event",
        "notification_read_state",
        "review_execution_attempt",
        "review_finding",
        "review_policy_config",
        "review_policy_promotion_evidence",
        "review_pull_request_head",
        "review_quality_baseline_snapshot",
        "review_repository_dimension",
        "review_repository_suppression",
        "review_repository_suppression_audit",
        "review_rule_config",
        "review_rule_policy_snapshot",
        "review_strategy_policy_snapshot",
        "llm_evaluation_report",
        "llm_evaluation_report_lifecycle_audit",
        "llm_model_release_audit",
        "llm_model_release_drift_audit",
        "llm_model_release_metric_snapshot",
        "sarif_import_batch",
        "secret_re_encryption_job",
        "secret_re_encryption_job_item",
        "review_task",
        "review_task_archive_summary",
        "review_bot_command_audit",
        "review_timeline",
        "system_setting_log",
        "system_settings_config"
    );

    public static Set<String> tenantTables() {
        return TENANT_TABLES;
    }

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(TenantProperties tenantProperties) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantHandler(tenantProperties)));
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    static final class TenantHandler implements TenantLineHandler {

        private final TenantProperties properties;

        TenantHandler(TenantProperties properties) {
            this.properties = properties;
        }

        @Override
        public Expression getTenantId() {
            Long tenantId = TenantContext.currentTenantId();
            if (tenantId == null) {
                throw new IllegalStateException("Tenant-scoped SQL requires an active tenant context");
            }
            return new LongValue(tenantId);
        }

        @Override
        public String getTenantIdColumn() {
            return "tenant_id";
        }

        @Override
        public boolean ignoreTable(String tableName) {
            if (!properties.isEnabled()) {
                return true;
            }
            String normalized = tableName == null
                ? ""
                : tableName.replace("`", "").trim().toLowerCase(Locale.ROOT);
            if (!TENANT_TABLES.contains(normalized)) {
                return true;
            }
            return PlatformTenantScope.isActive();
        }
    }
}
