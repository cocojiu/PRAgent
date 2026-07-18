package com.repoguard.agent.config;

import java.util.Locale;
import java.util.Objects;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Resolves the process role and enforces the currently supported horizontal-scaling boundary.
 */
public record RuntimeRoleContract(
    Mode role,
    DeploymentMode deploymentMode,
    int apiInstanceCount,
    boolean derivedFromLegacyFlags
) {

    public RuntimeRoleContract {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(deploymentMode, "deploymentMode");
        if (apiInstanceCount < 0) {
            throw new IllegalArgumentException("apiInstanceCount must not be negative");
        }
    }

    public boolean apiEnabled() {
        return role == Mode.API || role == Mode.COMBINED;
    }

    public boolean workerEnabled() {
        return role == Mode.WORKER || role == Mode.COMBINED;
    }

    public boolean schedulerEnabled() {
        return workerEnabled();
    }

    public static RuntimeRoleContract resolve(Environment environment) {
        Objects.requireNonNull(environment, "environment");

        String configuredRole = firstConfigured(
            environment,
            "app.runtime.role",
            "REPOGUARD_RUNTIME_ROLE"
        );
        String legacyApi = firstConfigured(
            environment,
            "app.runtime.api.enabled",
            "REPOGUARD_API_ENABLED"
        );
        String legacyWorker = firstConfigured(
            environment,
            "app.runtime.worker.enabled",
            "REPOGUARD_WORKER_ENABLED"
        );

        boolean hasLegacyFlags = legacyApi != null || legacyWorker != null;
        Mode role;
        boolean derivedFromLegacyFlags = false;
        if (configuredRole != null) {
            role = Mode.parse(configuredRole);
            if (hasLegacyFlags) {
                Mode legacyRole = roleFromLegacyFlags(legacyApi, legacyWorker);
                if (legacyRole != role) {
                    throw new IllegalStateException(
                        "app.runtime.role=" + role.value()
                            + " conflicts with legacy API/Worker flags resolved as " + legacyRole.value()
                    );
                }
            }
        } else if (hasLegacyFlags) {
            role = roleFromLegacyFlags(legacyApi, legacyWorker);
            derivedFromLegacyFlags = true;
        } else {
            role = Mode.COMBINED;
        }

        String configuredDeploymentMode = firstConfigured(
            environment,
            "app.runtime.deployment-mode",
            "REPOGUARD_DEPLOYMENT_MODE"
        );
        DeploymentMode deploymentMode = configuredDeploymentMode == null
            ? DeploymentMode.defaultFor(role)
            : DeploymentMode.parse(configuredDeploymentMode);
        validateDeploymentMode(role, deploymentMode);

        int defaultApiInstanceCount = role == Mode.WORKER ? 0 : 1;
        int apiInstanceCount = integerProperty(
            environment,
            defaultApiInstanceCount,
            "app.runtime.api.instance-count",
            "REPOGUARD_API_INSTANCE_COUNT"
        );
        validateApiScalingBoundary(role, apiInstanceCount);
        return new RuntimeRoleContract(role, deploymentMode, apiInstanceCount, derivedFromLegacyFlags);
    }

    private static Mode roleFromLegacyFlags(String legacyApi, String legacyWorker) {
        boolean apiEnabled = booleanValue(legacyApi, true, "REPOGUARD_API_ENABLED");
        boolean workerEnabled = booleanValue(legacyWorker, true, "REPOGUARD_WORKER_ENABLED");
        if (apiEnabled && workerEnabled) {
            return Mode.COMBINED;
        }
        if (apiEnabled) {
            return Mode.API;
        }
        if (workerEnabled) {
            return Mode.WORKER;
        }
        throw new IllegalStateException(
            "Runtime role cannot disable both API and Worker; configure app.runtime.role as api, worker, or combined"
        );
    }

    private static boolean booleanValue(String rawValue, boolean defaultValue, String propertyName) {
        if (rawValue == null) {
            return defaultValue;
        }
        return switch (rawValue.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1" -> true;
            case "false", "0" -> false;
            default -> throw new IllegalStateException(propertyName + " must be true, false, 1, or 0");
        };
    }

    private static int integerProperty(Environment environment, int defaultValue, String... propertyNames) {
        String rawValue = firstConfigured(environment, propertyNames);
        if (rawValue == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(rawValue);
        } catch (NumberFormatException ex) {
            throw new IllegalStateException(propertyNames[0] + " must be an integer", ex);
        }
    }

    private static void validateApiScalingBoundary(Mode role, int apiInstanceCount) {
        if (apiInstanceCount < 0) {
            throw new IllegalStateException("app.runtime.api.instance-count must not be negative");
        }
        if (role == Mode.WORKER && apiInstanceCount != 0) {
            throw new IllegalStateException(
                "Worker runtime requires app.runtime.api.instance-count=0"
            );
        }
        if (role != Mode.WORKER && apiInstanceCount != 1) {
            throw new IllegalStateException(
                "API runtime currently requires app.runtime.api.instance-count=1 because authentication/webhook "
                    + "rate limits and dashboard snapshots use process-local state"
            );
        }
    }

    private static void validateDeploymentMode(Mode role, DeploymentMode deploymentMode) {
        if (deploymentMode == DeploymentMode.MONOLITH && role != Mode.COMBINED) {
            throw new IllegalStateException(
                "Monolith deployment requires app.runtime.role=combined"
            );
        }
        if (deploymentMode == DeploymentMode.SPLIT && role == Mode.COMBINED) {
            throw new IllegalStateException(
                "Split deployment requires app.runtime.role=api or worker; combined would create duplicate consumers"
            );
        }
    }

    private static String firstConfigured(Environment environment, String... propertyNames) {
        for (String propertyName : propertyNames) {
            String value = environment.getProperty(propertyName);
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    public enum Mode {
        API("api"),
        WORKER("worker"),
        COMBINED("combined");

        private final String value;

        Mode(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        static Mode parse(String rawValue) {
            String normalized = rawValue == null ? "" : rawValue.trim().toLowerCase(Locale.ROOT);
            for (Mode candidate : values()) {
                if (candidate.value.equals(normalized)) {
                    return candidate;
                }
            }
            throw new IllegalStateException(
                "app.runtime.role must be one of api, worker, or combined"
            );
        }
    }

    public enum DeploymentMode {
        MONOLITH("monolith"),
        SPLIT("split");

        private final String value;

        DeploymentMode(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        static DeploymentMode defaultFor(Mode role) {
            return role == Mode.COMBINED ? MONOLITH : SPLIT;
        }

        static DeploymentMode parse(String rawValue) {
            String normalized = rawValue == null ? "" : rawValue.trim().toLowerCase(Locale.ROOT);
            for (DeploymentMode candidate : values()) {
                if (candidate.value.equals(normalized)) {
                    return candidate;
                }
            }
            throw new IllegalStateException(
                "app.runtime.deployment-mode must be monolith or split"
            );
        }
    }
}
