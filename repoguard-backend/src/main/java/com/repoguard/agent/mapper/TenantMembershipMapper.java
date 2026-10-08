package com.repoguard.agent.mapper;

import com.repoguard.agent.tenancy.TenantMembershipView;
import com.repoguard.agent.mapper.projection.AssignableReviewMember;
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

    /** Keep membership/account eligibility stable until the assignment transaction commits. */
    @Select("""
        select account.username
          from tenant_membership membership
          join tenant on tenant.id = membership.tenant_id
          join user_account account on account.id = membership.user_id
         where membership.tenant_id = #{tenantId}
           and account.username = #{username}
           and account.status = 'ACTIVE'
           and tenant.status = 'ACTIVE'
           and membership.role in ('ADMIN', 'PLATFORM_ADMIN', 'TENANT_ADMIN', 'REVIEWER')
         limit 1 for update
        """)
    String selectAssignableUsernameForUpdate(@Param("tenantId") Long tenantId, @Param("username") String username);

    @Select("""
        select account.id as userId, account.username
          from tenant_membership membership
          join tenant on tenant.id = membership.tenant_id
          join user_account account on account.id = membership.user_id
         where membership.tenant_id = #{tenantId}
           and account.status = 'ACTIVE' and tenant.status = 'ACTIVE'
           and membership.role in ('ADMIN', 'PLATFORM_ADMIN', 'TENANT_ADMIN', 'REVIEWER')
           and account.username like concat(#{prefix}, '%') escape '!'
         order by account.username, account.id
         limit 21
        """)
    List<AssignableReviewMember> selectAssignableMembers(
        @Param("tenantId") Long tenantId, @Param("prefix") String prefix);

    @Select("""
        select account.username
          from tenant_membership membership
          join tenant on tenant.id = membership.tenant_id
          join user_account account on account.id = membership.user_id
         where membership.tenant_id = #{tenantId} and account.id = #{userId}
           and account.status = 'ACTIVE' and tenant.status = 'ACTIVE'
           and membership.role in ('ADMIN', 'PLATFORM_ADMIN', 'TENANT_ADMIN', 'REVIEWER')
         limit 1 for update
        """)
    String selectAssignableUsernameByIdForUpdate(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

}
