package com.repoguard.agent.observability;

@FunctionalInterface
public interface ReviewFailureCategoryResolver {

    String failureCategory(RuntimeException failure);
}
