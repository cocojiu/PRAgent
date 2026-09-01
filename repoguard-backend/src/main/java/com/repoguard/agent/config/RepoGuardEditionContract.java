package com.repoguard.agent.config;

import java.util.Locale;
import java.util.Objects;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Resolves the product edition without making enterprise capabilities part of
 * the personal project's default startup path.
 */
public record RepoGuardEditionContract(Edition edition) {

    public RepoGuardEditionContract {
        Objects.requireNonNull(edition, "edition");
    }

    public boolean personal() {
        return edition == Edition.PERSONAL;
    }

    public boolean enterpriseEnabled() {
        return edition == Edition.ENTERPRISE_EXPERIMENTAL;
    }

    public static RepoGuardEditionContract resolve(Environment environment) {
        Objects.requireNonNull(environment, "environment");
        String configuredEdition = firstConfigured(
            environment,
            "app.edition",
            "REPOGUARD_EDITION"
        );
        return new RepoGuardEditionContract(
            configuredEdition == null ? Edition.PERSONAL : Edition.parse(configuredEdition)
        );
    }

    private static String firstConfigured(Environment environment, String... propertyNames) {
        for (String propertyName : propertyNames) {
            String value = environment.getProperty(propertyName);
            if (StringUtils.hasText(value)
                && !(value.trim().startsWith("${") && value.trim().endsWith("}"))) {
                return value.trim();
            }
        }
        return null;
    }

    public enum Edition {
        PERSONAL("personal"),
        ENTERPRISE_EXPERIMENTAL("enterprise-experimental");

        private final String value;

        Edition(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        static Edition parse(String rawValue) {
            String normalized = rawValue == null ? "" : rawValue.trim().toLowerCase(Locale.ROOT);
            for (Edition candidate : values()) {
                if (candidate.value.equals(normalized)) {
                    return candidate;
                }
            }
            throw new IllegalStateException(
                "app.edition must be personal or enterprise-experimental"
            );
        }
    }
}
