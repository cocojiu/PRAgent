package com.repoguard.agent.observability;

public interface ReviewTaskLogContextValues {

    Long taskId();

    String organization();

    String repository();

    Integer prNumber();

    String traceId();
}
