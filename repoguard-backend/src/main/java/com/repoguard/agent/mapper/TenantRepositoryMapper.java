package com.repoguard.agent.mapper;

import com.repoguard.agent.tenancy.TenantRepositoryBinding;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface TenantRepositoryMapper {

    @Select("""
        select binding.tenant_id as tenantId,
               tenant.tenant_key as tenantKey,
               binding.organization as organization,
               binding.repository as repository,
               binding.github_installation_id as githubInstallationId
          from tenant_repository binding
          join tenant on tenant.id = binding.tenant_id
         where lower(binding.organization) = lower(#{organization})
           and lower(binding.repository) = lower(#{repository})
           and binding.status = 'ACTIVE'
           and tenant.status = 'ACTIVE'
         limit 1
        """)
    TenantRepositoryBinding selectActiveRepository(
        @Param("organization") String organization,
        @Param("repository") String repository
    );

    @Select("""
        select binding.tenant_id as tenantId,
               tenant.tenant_key as tenantKey,
               binding.organization as organization,
               binding.repository as repository,
               binding.github_installation_id as githubInstallationId
          from tenant_repository binding
          join tenant on tenant.id = binding.tenant_id
         where binding.github_installation_id = #{installationId}
           and binding.status = 'ACTIVE'
           and tenant.status = 'ACTIVE'
         limit 1
        """)
    TenantRepositoryBinding selectActiveInstallation(@Param("installationId") Long installationId);
}
