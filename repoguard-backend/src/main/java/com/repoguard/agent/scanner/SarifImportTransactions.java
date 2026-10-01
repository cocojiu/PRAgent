package com.repoguard.agent.scanner;

import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared commit boundary for prepared findings and their optional CI upload record. */
@Component
public class SarifImportTransactions {
    private final TransactionTemplate transaction;

    public SarifImportTransactions(PlatformTransactionManager manager) {
        transaction = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public <T> T execute(Supplier<T> work) {
        return transaction.execute(_ -> work.get());
    }
}
