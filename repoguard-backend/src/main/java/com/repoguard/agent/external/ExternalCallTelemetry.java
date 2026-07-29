package com.repoguard.agent.external;

@FunctionalInterface
public interface ExternalCallTelemetry {

    void recordRetry(ExternalCallException failure, int attempt);
}
