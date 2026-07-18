package com.repoguard.agent.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public final class RuntimeRoleConditions {

    private RuntimeRoleConditions() {
    }

    public static final class Api implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return RuntimeRoleContract.resolve(context.getEnvironment()).apiEnabled();
        }
    }

    public static final class Worker implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return RuntimeRoleContract.resolve(context.getEnvironment()).workerEnabled();
        }
    }

    public static final class Scheduler implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return RuntimeRoleContract.resolve(context.getEnvironment()).schedulerEnabled();
        }
    }
}
