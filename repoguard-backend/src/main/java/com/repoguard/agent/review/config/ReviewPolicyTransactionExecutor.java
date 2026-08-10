package com.repoguard.agent.review.config;

import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs only the policy CAS and immutable snapshot/evidence writes in a short transaction.
 */
@Component
public class ReviewPolicyTransactionExecutor {

    private final TransactionTemplate writeTransaction;

    public ReviewPolicyTransactionExecutor(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transactionManager")
        );
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.writeTransaction = template;
    }

    private ReviewPolicyTransactionExecutor() {
        this.writeTransaction = null;
    }

    public static ReviewPolicyTransactionExecutor direct() {
        return new ReviewPolicyTransactionExecutor();
    }

    public <T> T write(Supplier<T> operation) {
        Supplier<T> action = Objects.requireNonNull(operation, "operation");
        if (writeTransaction == null) {
            return action.get();
        }
        return writeTransaction.execute(status -> action.get());
    }
}
