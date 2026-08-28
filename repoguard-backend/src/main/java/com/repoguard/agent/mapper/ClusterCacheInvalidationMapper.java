package com.repoguard.agent.mapper;

import com.repoguard.agent.cache.TenantCacheVersion;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ClusterCacheInvalidationMapper {

    @Insert("""
        insert into tenant_cache_version(
            tenant_id,
            cache_version,
            created_at
        ) values (
            #{tenantId},
            1,
            current_timestamp(6)
        )
        on duplicate key update
            cache_version = cache_version + 1,
            updated_at = current_timestamp(6)
        """)
    int increment(@Param("tenantId") long tenantId);

    @Select("""
        select tenant_id as tenantId,
               cache_version as cacheVersion,
               updated_at as updatedAt
        from tenant_cache_version
        where tenant_id > #{afterTenantId}
        order by tenant_id
        limit #{limit}
        """)
    List<TenantCacheVersion> selectPage(
        @Param("afterTenantId") long afterTenantId,
        @Param("limit") int limit
    );
}
