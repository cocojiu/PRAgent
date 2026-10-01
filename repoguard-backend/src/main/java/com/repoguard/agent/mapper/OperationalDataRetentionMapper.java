package com.repoguard.agent.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface OperationalDataRetentionMapper {

    String FEEDBACK_PAYLOAD_CANDIDATE = "tenant_id = #{tenantId} and payload_purged_at is null "
        + "and retention_protected = false and status in ('APPLIED', 'IGNORED') and updated_at < #{cutoff} "
        + "and not exists (select 1 from review_repository_suppression s join review_finding f "
        + "on f.tenant_id = s.tenant_id and f.rule_id collate utf8mb4_unicode_ci = s.rule_id "
        + "where s.tenant_id = #{tenantId} and f.id = github_feedback_event.finding_id "
        + "and s.organization = github_feedback_event.owner and s.repository = github_feedback_event.repository)";
    String RUN_PAYLOAD_CANDIDATE = "tenant_id = #{tenantId} and payload_purged_at is null "
        + "and retention_protected = false and status in ('COMPLETE', 'FAILED', 'CANCELLED') "
        + "and finished_at < #{cutoff} and report_id is null "
        + "and (diagnostics_json is null or case when json_valid(diagnostics_json) "
        + "then json_type(json_extract(diagnostics_json, '$.sampleIds')) = 'ARRAY' else false end)";

    @Update("update github_feedback_event set note = '', actor = '', actor_id = 0, "
        + "payload_purged_at = current_timestamp(6) where " + FEEDBACK_PAYLOAD_CANDIDATE
        + " order by updated_at, id limit #{limit}")
    int purgeFeedbackPayload(@Param("tenantId") long tenantId, @Param("cutoff") LocalDateTime cutoff,
        @Param("limit") int limit);

    @Update("update llm_evaluation_run set data_directory = '', operator = '', "
        + "diagnostics_json = case when diagnostics_json is null then null else "
        + "json_object('sampleIds', json_extract(diagnostics_json, '$.sampleIds'), 'samples', json_array()) end, "
        + "payload_purged_at = current_timestamp(6) where " + RUN_PAYLOAD_CANDIDATE
        + " order by finished_at, id limit #{limit}")
    int purgeEvaluationRunPayload(@Param("tenantId") long tenantId, @Param("cutoff") LocalDateTime cutoff,
        @Param("limit") int limit);

    @Select("select count(*) as candidateCount, min(updated_at) as oldestAt, "
        + "coalesce(sum(octet_length(note) + octet_length(actor)), 0) as payloadBytes "
        + "from github_feedback_event where " + FEEDBACK_PAYLOAD_CANDIDATE)
    PayloadPreview previewFeedbackPayload(@Param("tenantId") long tenantId, @Param("cutoff") LocalDateTime cutoff);

    @Select("select count(*) as candidateCount, min(finished_at) as oldestAt, "
        + "coalesce(sum(octet_length(data_directory) + octet_length(operator) + "
        + "coalesce(octet_length(diagnostics_json), 0)), 0) as payloadBytes "
        + "from llm_evaluation_run where " + RUN_PAYLOAD_CANDIDATE)
    PayloadPreview previewEvaluationRunPayload(@Param("tenantId") long tenantId, @Param("cutoff") LocalDateTime cutoff);

    record PayloadPreview(long candidateCount, LocalDateTime oldestAt, long payloadBytes) { }

    @Delete("delete from user_refresh_token where id in (select id from (select id from user_refresh_token where expires_at < #{cutoff} order by expires_at, id limit #{limit}) c)")
    int deleteRefreshTokens(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    @Delete("delete from user_login_audit where id in (select id from (select id from user_login_audit where created_at < #{cutoff} order by created_at, id limit #{limit}) c)")
    int deleteLoginAudits(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    @Delete("delete from user_operation_audit where id in (select id from (select id from user_operation_audit where created_at < #{cutoff} order by created_at, id limit #{limit}) c)")
    int deleteUserOperationAudits(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    @Delete("delete from admin_operation_audit where id in (select id from (select id from admin_operation_audit where created_at < #{cutoff} order by created_at, id limit #{limit}) c)")
    int deleteAdminOperationAudits(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    @Delete("delete from system_setting_log where id in (select id from (select id from system_setting_log where created_at < #{cutoff} order by created_at, id limit #{limit}) c)")
    int deleteSystemSettingLogs(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    @Delete("delete from notification_delivery_log where id in (select id from (select id from notification_delivery_log where created_at < #{cutoff} order by created_at, id limit #{limit}) c)")
    int deleteNotificationDeliveries(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    @Delete("delete from notification_event where id in (select id from (select e.id from notification_event e where e.created_at < #{cutoff} and not exists (select 1 from notification_delivery_log d where d.event_id = e.id) order by e.created_at, e.id limit #{limit}) c)")
    int deleteNotificationEvents(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    @Delete("delete from tenant_quota_usage where tenant_id = #{tenantId} and usage_date < #{cutoff} order by usage_date limit #{limit}")
    int deleteTenantQuotaUsage(
        @Param("tenantId") long tenantId,
        @Param("cutoff") LocalDateTime cutoff,
        @Param("limit") int limit
    );

    @Delete("delete from operational_data_cleanup_audit where id in (select id from (select id from operational_data_cleanup_audit where created_at < #{cutoff} order by created_at, id limit #{limit}) c)")
    int deleteCleanupAudits(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    @Insert("insert into operational_data_cleanup_audit(tenant_id, table_name, cutoff_at, deleted_rows, status, failure_category, created_at) values(#{tenantId}, #{tableName}, #{cutoff}, #{deletedRows}, #{status}, #{failureCategory}, now())")
    int insertAudit(
        @Param("tenantId") Long tenantId,
        @Param("tableName") String tableName,
        @Param("cutoff") LocalDateTime cutoff,
        @Param("deletedRows") int deletedRows,
        @Param("status") String status,
        @Param("failureCategory") String failureCategory
    );
}
