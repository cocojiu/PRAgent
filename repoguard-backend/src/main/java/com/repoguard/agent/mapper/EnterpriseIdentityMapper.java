package com.repoguard.agent.mapper;

import com.repoguard.agent.identity.EnterpriseIdentityView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface EnterpriseIdentityMapper {

    @Select("""
        select identity.tenant_id as tenantId,
               account.id as userId,
               account.username as username,
               membership.role as role,
               account.status as status,
               account.session_version as sessionVersion
          from enterprise_identity identity
          join user_account account on account.id = identity.user_id
          join tenant_membership membership
            on membership.tenant_id = identity.tenant_id
           and membership.user_id = identity.user_id
          join tenant on tenant.id = identity.tenant_id
         where binary identity.issuer = binary #{issuer}
           and identity.subject = #{subject}
           and identity.status = 'ACTIVE'
           and tenant.status = 'ACTIVE'
         limit 1
        """)
    EnterpriseIdentityView selectActiveIdentity(
        @Param("issuer") String issuer,
        @Param("subject") String subject
    );
}
