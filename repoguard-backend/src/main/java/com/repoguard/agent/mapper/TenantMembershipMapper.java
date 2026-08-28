package com.repoguard.agent.mapper;

import com.repoguard.agent.tenancy.TenantMembershipView;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface TenantMembershipMapper {

    @Select("""
        select membership.tenant_id as tenantId,
               tenant.tenant_key as tenantKey,
               membership.role as role,
               membership.default_tenant as defaultTenant
          from tenant_membership membership
          join tenant on tenant.id = membership.tenant_id
         where membership.user_id = #{userId}
           and tenant.status = 'ACTIVE'
         order by membership.default_tenant desc, membership.tenant_id asc
        """)
    List<TenantMembershipView> selectActiveMemberships(@Param("userId") Long userId);
}
