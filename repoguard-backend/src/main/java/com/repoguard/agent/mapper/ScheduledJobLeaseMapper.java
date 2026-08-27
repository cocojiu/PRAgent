package com.repoguard.agent.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ScheduledJobLeaseMapper {

    @Insert("""
        insert into scheduled_job_lease (
            scope_key,
            tenant_id,
            job_name,
            owner_id,
            fencing_token,
            locked_until,
            created_at,
            updated_at
        ) values (
            #{scopeKey},
            #{tenantId},
            #{jobName},
            #{ownerId},
            1,
            date_add(current_timestamp(6), interval #{leaseSeconds} second),
            current_timestamp(6),
            current_timestamp(6)
        )
        on duplicate key update
            fencing_token = if(locked_until <= values(updated_at), fencing_token + 1, fencing_token),
            updated_at = if(locked_until <= values(updated_at), values(updated_at), updated_at),
            owner_id = if(locked_until <= values(updated_at), values(owner_id), owner_id),
            locked_until = if(locked_until <= values(updated_at), values(locked_until), locked_until)
        """)
    int acquireOrCreate(
        @Param("scopeKey") String scopeKey,
        @Param("tenantId") Long tenantId,
        @Param("jobName") String jobName,
        @Param("ownerId") String ownerId,
        @Param("leaseSeconds") long leaseSeconds
    );

    @Select("""
        select owner_id as ownerId,
               fencing_token as fencingToken
        from scheduled_job_lease
        where scope_key = #{scopeKey}
        """)
    LeaseOwner selectOwner(@Param("scopeKey") String scopeKey);

    @Update("""
        update scheduled_job_lease
        set locked_until = date_add(current_timestamp(6), interval #{leaseSeconds} second),
            updated_at = current_timestamp(6)
        where scope_key = #{scopeKey}
          and owner_id = #{ownerId}
          and fencing_token = #{fencingToken}
          and locked_until > current_timestamp(6)
        """)
    int renew(
        @Param("scopeKey") String scopeKey,
        @Param("ownerId") String ownerId,
        @Param("fencingToken") long fencingToken,
        @Param("leaseSeconds") long leaseSeconds
    );

    @Select("""
        select count(*)
        from scheduled_job_lease
        where scope_key = #{scopeKey}
          and owner_id = #{ownerId}
          and fencing_token = #{fencingToken}
          and locked_until > current_timestamp(6)
        """)
    int isHeld(
        @Param("scopeKey") String scopeKey,
        @Param("ownerId") String ownerId,
        @Param("fencingToken") long fencingToken
    );

    @Update("""
        update scheduled_job_lease
        set owner_id = null,
            locked_until = current_timestamp(6),
            updated_at = current_timestamp(6)
        where scope_key = #{scopeKey}
          and owner_id = #{ownerId}
          and fencing_token = #{fencingToken}
        """)
    int release(
        @Param("scopeKey") String scopeKey,
        @Param("ownerId") String ownerId,
        @Param("fencingToken") long fencingToken
    );

    record LeaseOwner(String ownerId, long fencingToken) {
    }
}
