package com.repoguard.agent.scm;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Resolves an SCM adapter by the persisted provider key. */
@Service
public class ScmProviderRegistry {

    private final Map<String, ScmProvider> providers;

    public ScmProviderRegistry(List<ScmProvider> providers) {
        List<ScmProvider> candidates = providers == null ? List.of() : providers;
        this.providers = candidates.stream()
            .filter(Objects::nonNull)
            .collect(Collectors.toUnmodifiableMap(
                provider -> normalize(provider.providerKey()),
                Function.identity(),
                (left, right) -> {
                    throw new IllegalStateException("Duplicate SCM provider: " + left.providerKey());
                }
            ));
    }

    public List<ScmProviderDescriptor> descriptors() {
        return providers.values().stream()
            .sorted(Comparator.comparing(ScmProvider::providerKey))
            .map(provider -> descriptor(provider))
            .toList();
    }

    public ScmProvider require(String provider) {
        ScmProvider resolved = providers.get(normalize(provider));
        if (resolved == null) {
            throw new IllegalArgumentException("Unsupported SCM provider: " + provider);
        }
        return resolved;
    }

    private String normalize(String provider) {
        if (!StringUtils.hasText(provider)) {
            throw new IllegalArgumentException("SCM provider is required");
        }
        return provider.trim().toUpperCase(Locale.ROOT);
    }

    private ScmProviderDescriptor descriptor(ScmProvider provider) {
        ScmIntegrationSettings settings = provider.settings();
        ScmRepositoryRef repository = provider.configuredRepository();
        return new ScmProviderDescriptor(
            provider.providerKey(),
            settings != null && settings.configured(),
            repository == null ? null : repository.fullName()
        );
    }

    public record ScmProviderDescriptor(String provider, boolean configured, String repository) {
    }
}
