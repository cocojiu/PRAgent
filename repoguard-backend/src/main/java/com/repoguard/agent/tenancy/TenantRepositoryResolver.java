package com.repoguard.agent.tenancy;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.mapper.TenantRepositoryMapper;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class TenantRepositoryResolver {

    private final TenantRepositoryMapper repositoryMapper;
    private final TenantProperties properties;

    public TenantRepositoryResolver(TenantRepositoryMapper repositoryMapper, TenantProperties properties) {
        this.repositoryMapper = Objects.requireNonNull(repositoryMapper, "repositoryMapper");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public TenantRepositoryBinding resolve(String organization, String repository, Long installationId) {
        if (!properties.isEnabled()) {
            return new TenantRepositoryBinding(
                TenantContext.DEFAULT_TENANT_ID,
                "default",
                organization,
                repository,
                installationId
            );
        }
        TenantRepositoryBinding byRepository = repositoryMapper.selectActiveRepository(organization, repository);
        if (byRepository == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Repository is not assigned to an active tenant");
        }
        if (installationId == null) {
            return byRepository;
        }
        TenantRepositoryBinding byInstallation = repositoryMapper.selectActiveInstallation(installationId);
        if (byInstallation == null || !byRepository.tenantId().equals(byInstallation.tenantId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "GitHub installation is not assigned to this tenant");
        }
        return byRepository;
    }
}
