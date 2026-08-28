package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

class RuntimeRoleBoundaryContractTest {

    @Test
    void asynchronousEntrypointsDeclareWorkerOrSchedulerCapability() {
        Set<Class<?>> components = productionComponents();
        List<Class<?>> scheduledComponents = components.stream()
            .filter(component -> hasAnnotatedMethod(component, Scheduled.class))
            .sorted((left, right) -> left.getName().compareTo(right.getName()))
            .toList();
        List<Class<?>> listenerComponents = components.stream()
            .filter(component -> hasAnnotatedMethod(component, RabbitListener.class))
            .sorted((left, right) -> left.getName().compareTo(right.getName()))
            .toList();

        assertThat(scheduledComponents).isNotEmpty();
        for (Class<?> component : scheduledComponents) {
            boolean schedulerRuntime = AnnotatedElementUtils.hasAnnotation(
                component,
                SchedulerRuntimeEnabled.class
            );
            boolean replicaRuntime = AnnotatedElementUtils.hasAnnotation(
                component,
                ReplicaRuntimeEnabled.class
            );
            assertThat(schedulerRuntime ^ replicaRuntime)
                .as(
                    "scheduled component %s must declare exactly one Scheduler or per-replica capability",
                    component.getName()
                )
                .isTrue();
            assertThat(AnnotatedElementUtils.hasAnnotation(component, WorkerRuntimeEnabled.class))
                .as("scheduled component %s must not be coupled to the message-consumer capability", component.getName())
                .isFalse();
        }
        assertThat(scheduledComponents.stream()
            .filter(component -> AnnotatedElementUtils.hasAnnotation(component, ReplicaRuntimeEnabled.class))
            .map(Class::getName))
            .containsExactly("com.repoguard.agent.cache.ClusterCacheInvalidationPoller");

        assertThat(listenerComponents).isNotEmpty();
        for (Class<?> component : listenerComponents) {
            assertThat(AnnotatedElementUtils.hasAnnotation(component, WorkerRuntimeEnabled.class))
                .as("Rabbit listener %s must declare Worker capability", component.getName())
                .isTrue();
            assertThat(AnnotatedElementUtils.hasAnnotation(component, SchedulerRuntimeEnabled.class))
                .as("Rabbit listener %s must not be coupled to the Scheduler capability", component.getName())
                .isFalse();
        }
    }

    private Set<Class<?>> productionComponents() {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class, true, true));
        ClassLoader classLoader = getClass().getClassLoader();
        return scanner.findCandidateComponents("com.repoguard.agent").stream()
            .map(definition -> ClassUtils.resolveClassName(definition.getBeanClassName(), classLoader))
            .collect(Collectors.toSet());
    }

    private boolean hasAnnotatedMethod(Class<?> component, Class<? extends Annotation> annotationType) {
        for (Method method : component.getDeclaredMethods()) {
            if (AnnotatedElementUtils.hasAnnotation(method, annotationType)) {
                return true;
            }
        }
        return false;
    }
}
