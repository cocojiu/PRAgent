package com.repoguard.agent.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface TenantCatalogMapper {

    @Select("""
        select count(*)
        from tenant
        where id = #{tenantId}
          and status = 'ACTIVE'
        """)
    int countActive(@Param("tenantId") long tenantId);

    @Select("""
        select id
        from tenant
        where status = 'ACTIVE'
          and id > #{afterTenantId}
        order by id
        limit #{limit}
        """)
    List<Long> selectActiveTenantIdsAfter(
        @Param("afterTenantId") long afterTenantId,
        @Param("limit") int limit
    );
}
