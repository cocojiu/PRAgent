package com.repoguard.agent.config;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.util.StringUtils;

public final class RuntimeProfilePolicy {

    private static final Set<String> LOCAL_TEST_PROFILES = Set.of("dev", "local", "test");

    private RuntimeProfilePolicy() {
    }

    public static void requireExplicitProfiles(String[] activeProfiles) {
        List<String> profiles = normalizedProfiles(activeProfiles);
        if (profiles.isEmpty()) {
            throw new IllegalStateException(
                "An explicit Spring profile is required; use dev,local for local development, test for tests, or prod for production"
            );
        }
        boolean hasLocalTestProfile = profiles.stream().anyMatch(LOCAL_TEST_PROFILES::contains);
        boolean hasProductionLikeProfile = profiles.stream().anyMatch(profile -> !LOCAL_TEST_PROFILES.contains(profile));
        if (hasLocalTestProfile && hasProductionLikeProfile) {
            throw new IllegalStateException("Local/test Spring profiles cannot be combined with production-like profiles");
        }
    }

    public static boolean isProductionLike(String[] activeProfiles) {
        return normalizedProfiles(activeProfiles).stream()
            .anyMatch(profile -> !LOCAL_TEST_PROFILES.contains(profile));
    }

    private static List<String> normalizedProfiles(String[] activeProfiles) {
        if (activeProfiles == null) {
            return List.of();
        }
        return Arrays.stream(activeProfiles)
            .filter(StringUtils::hasText)
            .map(String::trim)
            .map(profile -> profile.toLowerCase(Locale.ROOT))
            .distinct()
            .toList();
    }
}
