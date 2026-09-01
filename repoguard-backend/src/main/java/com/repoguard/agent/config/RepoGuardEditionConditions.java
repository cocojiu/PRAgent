package com.repoguard.agent.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public final class RepoGuardEditionConditions {

    private RepoGuardEditionConditions() {
    }

    public static final class Enterprise implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return RepoGuardEditionContract.resolve(context.getEnvironment()).enterpriseEnabled();
        }
    }
}
