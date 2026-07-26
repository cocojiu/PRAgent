package com.repoguard.agent.observability;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.messaging.ReviewTaskMessage;
import org.slf4j.MDC;

public final class LogContext {

    public static final String TASK_ID = "taskId";
    public static final String PR_NUMBER = "prNumber";
    public static final String REPOSITORY = "repository";
    public static final String TRACE_ID = "traceId";

    private LogContext() {
    }

    public static Scope withReviewTask(ReviewTask task) {
        return withReviewTask(task, null);
    }

    public static Scope withReviewTask(ReviewTask task, String traceId) {
        if (task == null) {
            return new Scope(null, null, null, null);
        }
        return put(
            value(task.getId()),
            value(task.getPrNumber()),
            repository(task.getOrganization(), task.getRepository()),
            traceId
        );
    }

    public static Scope withReviewTaskMessage(ReviewTaskMessage message) {
        if (message == null) {
            return new Scope(null, null, null, null);
        }
        return put(
            value(message.taskId()),
            value(message.prNumber()),
            repository(message.organization(), message.repository()),
            message.traceId()
        );
    }

    public static String currentTraceId() {
        return MDC.get(TRACE_ID);
    }

    private static Scope put(String taskId, String prNumber, String repository, String traceId) {
        String previousTaskId = MDC.get(TASK_ID);
        String previousPrNumber = MDC.get(PR_NUMBER);
        String previousRepository = MDC.get(REPOSITORY);
        String previousTraceId = MDC.get(TRACE_ID);
        putOrRemove(TASK_ID, taskId);
        putOrRemove(PR_NUMBER, prNumber);
        putOrRemove(REPOSITORY, repository);
        if (traceId != null) {
            putOrRemove(TRACE_ID, traceId);
        }
        return new Scope(previousTaskId, previousPrNumber, previousRepository, previousTraceId);
    }

    private static void putOrRemove(String key, String value) {
        if (value == null || value.isBlank()) {
            MDC.remove(key);
            return;
        }
        MDC.put(key, value);
    }

    private static String repository(String organization, String repository) {
        String owner = safePart(organization);
        String repo = safePart(repository);
        if (owner == null && repo == null) {
            return null;
        }
        return (owner == null ? "<unknown>" : owner) + "/" + (repo == null ? "<unknown>" : repo);
    }

    private static String safePart(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String value(Object value) {
        return value == null ? null : value.toString();
    }

    public static final class Scope implements AutoCloseable {

        private final String previousTaskId;
        private final String previousPrNumber;
        private final String previousRepository;
        private final String previousTraceId;

        private Scope(String previousTaskId, String previousPrNumber, String previousRepository, String previousTraceId) {
            this.previousTaskId = previousTaskId;
            this.previousPrNumber = previousPrNumber;
            this.previousRepository = previousRepository;
            this.previousTraceId = previousTraceId;
        }

        @Override
        public void close() {
            putOrRemove(TASK_ID, previousTaskId);
            putOrRemove(PR_NUMBER, previousPrNumber);
            putOrRemove(REPOSITORY, previousRepository);
            putOrRemove(TRACE_ID, previousTraceId);
        }
    }
}
