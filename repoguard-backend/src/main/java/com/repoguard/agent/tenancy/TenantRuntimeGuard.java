package com.repoguard.agent.tenancy;

import com.repoguard.agent.mapper.TenantCatalogMapper;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class TenantRuntimeGuard {

    private final TenantCatalogMapper tenantCatalogMapper;

    public TenantRuntimeGuard(TenantCatalogMapper tenantCatalogMapper) {
        this.tenantCatalogMapper = Objects.requireNonNull(tenantCatalogMapper, "tenantCatalogMapper");
    }

    public void requireActive(long tenantId) {
        if (tenantId < 1 || tenantCatalogMapper.countActive(tenantId) != 1) {
            throw new TenantInactiveException(tenantId);
        }
    }
}
