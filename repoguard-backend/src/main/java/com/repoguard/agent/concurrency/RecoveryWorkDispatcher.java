package com.repoguard.agent.concurrency;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class RecoveryWorkDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(RecoveryWorkDispatcher.class);

    private final Executor delegate;

    @Autowired
    public RecoveryWorkDispatcher(
        @Qualifier(RecoveryWorkExecutorConfig.RECOVERY_WORK_EXECUTOR) ExecutorService delegate
    ) {
        this((Executor) delegate);
    }

    public RecoveryWorkDispatcher(Executor delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public boolean submit(String operation, Runnable work) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(work, "work");
        try {
            delegate.execute(() -> runObserved(operation, work));
            return true;
        } catch (RejectedExecutionException ex) {
            LOGGER.warn(
                "Recovery work rejected operation={} result=executor_rejected",
                operation
            );
            return false;
        }
    }

    private void runObserved(String operation, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException ex) {
            LOGGER.error(
                "Recovery work failed operation={} result=unexpected_failure exceptionType={}",
                operation,
                ex.getClass().getName(),
                ex
            );
        }
    }
}
