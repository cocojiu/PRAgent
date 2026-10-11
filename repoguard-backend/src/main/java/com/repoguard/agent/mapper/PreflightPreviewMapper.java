package com.repoguard.agent.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

/** Separate preview state: never reads or writes formal reviews, findings or dashboard data. */
@com.baomidou.mybatisplus.annotation.InterceptorIgnore(tenantLine = "true")
public interface PreflightPreviewMapper {
    @Select("select id from tenant where id = #{tenant} and status = 'ACTIVE' for update")
    Long lockTenant(@Param("tenant") long tenant);

    @Select("""
        select count(*) from preflight_preview where tenant_id = #{tenant}
        and created_at >= utc_timestamp(6) - interval 1 day
        and (#{llmOnly} = false or llm_requested = true)
        """)
    int countRecent(@Param("tenant") long tenant, @Param("llmOnly") boolean llmOnly);

    @Select("""
        select response_ciphertext from preflight_preview
        where tenant_id = #{tenant} and user_id = #{user} and request_sha256 = #{sha}
        and status = 'COMPLETED' and response_expires_at > utc_timestamp(6)
        """)
    String result(@Param("tenant") long tenant, @Param("user") long user, @Param("sha") String sha);

    @Select("""
        select count(*) from preflight_preview where tenant_id = #{tenant}
        and user_id = #{user} and request_sha256 = #{sha}
        """)
    int exists(@Param("tenant") long tenant, @Param("user") long user, @Param("sha") String sha);

    @Insert("""
        insert into preflight_preview
        (tenant_id, user_id, request_sha256, credential_nonce, repository, llm_requested, status,
         created_at, response_expires_at, dedup_expires_at)
        values (#{tenant}, #{user}, #{sha}, #{nonce}, #{repo}, #{llm}, 'RUNNING',
         utc_timestamp(6), utc_timestamp(6) + interval 15 minute, utc_timestamp(6) + interval 1 day)
        """)
    int claim(@Param("tenant") long tenant, @Param("user") long user, @Param("sha") String sha,
        @Param("nonce") String nonce, @Param("repo") String repo, @Param("llm") boolean llm);

    @Update("""
        update preflight_preview set status = 'COMPLETED', response_ciphertext = #{response}
        where tenant_id = #{tenant} and user_id = #{user} and request_sha256 = #{sha} and status = 'RUNNING'
        """)
    int complete(@Param("tenant") long tenant, @Param("user") long user,
        @Param("sha") String sha, @Param("response") String response);

    @Update("""
        update preflight_preview set status = 'FAILED'
        where tenant_id = #{tenant} and user_id = #{user} and request_sha256 = #{sha} and status = 'RUNNING'
        """)
    int fail(@Param("tenant") long tenant, @Param("user") long user, @Param("sha") String sha);

    @Update("""
        update preflight_preview set response_ciphertext = null
        where tenant_id = #{tenant} and response_expires_at <= utc_timestamp(6)
        and response_ciphertext is not null order by response_expires_at limit 100
        """)
    int purgeResponses(@Param("tenant") long tenant);

    @Delete("""
        delete from preflight_preview where tenant_id = #{tenant}
        and dedup_expires_at <= utc_timestamp(6) order by dedup_expires_at limit 100
        """)
    int purgeExpired(@Param("tenant") long tenant);
    @org.apache.ibatis.annotations.Options(timeout = 3)
    @Update("""
        update preflight_preview set response_ciphertext = null
        where response_expires_at <= utc_timestamp(6) and response_ciphertext is not null
        order by response_expires_at limit 100
        """)
    int purgeGlobalResponses();

    @org.apache.ibatis.annotations.Options(timeout = 3)
    @Delete("""
        delete from preflight_preview where dedup_expires_at <= utc_timestamp(6)
        order by dedup_expires_at limit 100
        """)
    int purgeGlobalExpired();
}
