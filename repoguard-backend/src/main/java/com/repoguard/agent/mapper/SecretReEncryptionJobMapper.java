package com.repoguard.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.repoguard.agent.entity.SecretReEncryptionJob;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface SecretReEncryptionJobMapper extends BaseMapper<SecretReEncryptionJob> {

    @Select("""
        select *
        from secret_re_encryption_job
        where tenant_id = #{tenantId}
          and (
            (
                status in ('PENDING', 'RETRY_WAIT')
                and (next_retry_at is null or next_retry_at <= #{now})
            )
            or (
                status = 'RUNNING'
                and (lease_until is null or lease_until <= #{now})
            )
        )
        order by id
        limit 1
        """)
    SecretReEncryptionJob selectDueJob(
        @Param("tenantId") long tenantId,
        @Param("now") LocalDateTime now
    );

    @Update("""
        update secret_re_encryption_job
        set status = 'RUNNING',
            claimed_by = #{ownerId},
            claimed_at = #{now},
            lease_until = #{leaseUntil},
            next_retry_at = null,
            updated_at = #{now}
        where id = #{jobId}
          and tenant_id = #{tenantId}
          and (
              (
                  status in ('PENDING', 'RETRY_WAIT')
                  and (next_retry_at is null or next_retry_at <= #{now})
              )
              or (
                  status = 'RUNNING'
                  and (lease_until is null or lease_until <= #{now})
              )
          )
        """)
    int claim(
        @Param("jobId") Long jobId,
        @Param("tenantId") long tenantId,
        @Param("ownerId") String ownerId,
        @Param("now") LocalDateTime now,
        @Param("leaseUntil") LocalDateTime leaseUntil
    );
}
