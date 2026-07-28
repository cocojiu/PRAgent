package com.repoguard.agent.integration.connection;

record ConnectionProbeResult(Boolean healthy, String status, String message) {
}
