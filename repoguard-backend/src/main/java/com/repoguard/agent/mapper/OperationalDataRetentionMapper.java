package com.repoguard.agent.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface OperationalDataRetentionMapper {

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
