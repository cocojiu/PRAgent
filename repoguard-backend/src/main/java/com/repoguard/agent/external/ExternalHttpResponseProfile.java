package com.repoguard.agent.external;

public enum ExternalHttpResponseProfile {

    GITHUB("github", 8 * 1024 * 1024),
    GITLAB("gitlab", 8 * 1024 * 1024),
    GITEE("gitee", 8 * 1024 * 1024),
    BITBUCKET("bitbucket", 8 * 1024 * 1024),
    LLM("llm", 4 * 1024 * 1024),
    CONNECTION_PROBE("connection_probe", 256 * 1024),
    NOTIFICATION("notification", 256 * 1024);

    private final String metricTag;
    private final int maxBytes;

    ExternalHttpResponseProfile(String metricTag, int maxBytes) {
        this.metricTag = metricTag;
        this.maxBytes = maxBytes;
    }

    public String metricTag() {
        return metricTag;
    }

    public int maxBytes() {
        return maxBytes;
    }
}
