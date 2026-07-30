package com.repoguard.agent.external;

public class ExternalCallException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String system;
    private final String category;
    private final boolean retryable;
    private final Integer statusCode;

    public ExternalCallException(
        String system,
        String category,
        boolean retryable,
        Integer statusCode,
        String detail,
        Throwable cause
    ) {
        super(buildMessage(system, category, retryable, statusCode, detail), cause);
        this.system = system;
        this.category = category;
        this.retryable = retryable;
        this.statusCode = statusCode;
    }

    public String getSystem() {
        return system;
    }

    public String getCategory() {
        return category;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    private static String buildMessage(
        String system,
        String category,
        boolean retryable,
        Integer statusCode,
        String detail
    ) {
        StringBuilder message = new StringBuilder(system)
            .append(" external call failed: category=")
            .append(category)
            .append(" retryable=")
            .append(retryable);
        if (statusCode != null) {
            message.append(" status=").append(statusCode);
        }
        if (detail != null && !detail.isBlank()) {
            message.append(" detail=").append(detail.replaceAll("\\s+", " ").trim());
        }
        return message.toString();
    }
}
