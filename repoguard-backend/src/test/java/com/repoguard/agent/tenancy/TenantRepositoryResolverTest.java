package com.repoguard.agent.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.mapper.TenantRepositoryMapper;
import org.junit.jupiter.api.Test;

class TenantRepositoryResolverTest {

    private final TenantRepositoryMapper mapper = mock(TenantRepositoryMapper.class);
    private final TenantProperties properties = new TenantProperties();
    private final TenantRepositoryResolver resolver = new TenantRepositoryResolver(mapper, properties);

    @Test
    void disabledTenancyPreservesSingleTenantCompatibility() {
        TenantRepositoryBinding binding = resolver.resolve("openai", "repoguard", 77L);

        assertThat(binding.tenantId()).isEqualTo(1L);
        assertThat(binding.githubInstallationId()).isEqualTo(77L);
        verifyNoInteractions(mapper);
    }

    @Test
    void resolvesRepositoryAndMatchingInstallation() {
        properties.setEnabled(true);
        TenantRepositoryBinding repository = binding(8L, 77L);
        when(mapper.selectActiveRepository("openai", "repoguard")).thenReturn(repository);
        when(mapper.selectActiveInstallation(77L)).thenReturn(repository);

        assertThat(resolver.resolve("openai", "repoguard", 77L)).isEqualTo(repository);
    }

    @Test
    void rejectsUnassignedRepository() {
        properties.setEnabled(true);

        assertThatThrownBy(() -> resolver.resolve("unknown", "repo", null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("not assigned");
    }

    @Test
    void rejectsInstallationOwnedByAnotherTenant() {
        properties.setEnabled(true);
        when(mapper.selectActiveRepository("openai", "repoguard")).thenReturn(binding(8L, 77L));
        when(mapper.selectActiveInstallation(77L)).thenReturn(binding(9L, 77L));

        assertThatThrownBy(() -> resolver.resolve("openai", "repoguard", 77L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("this tenant");
    }

    private TenantRepositoryBinding binding(long tenantId, long installationId) {
        return new TenantRepositoryBinding(
            tenantId,
            "tenant-" + tenantId,
            "openai",
            "repoguard",
            installationId
        );
    }
}
