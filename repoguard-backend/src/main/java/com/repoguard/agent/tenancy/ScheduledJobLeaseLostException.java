package com.repoguard.agent.tenancy;

public class ScheduledJobLeaseLostException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public ScheduledJobLeaseLostException(String message) {
        super(message);
    }

    public ScheduledJobLeaseLostException(String message, Throwable cause) {
        super(message, cause);
    }
}
