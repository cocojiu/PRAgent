package com.repoguard.agent.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import com.repoguard.agent.review.ReviewPolicySettings;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/** Behavior tests cover outcomes; these ratchets cover hidden coupling and change propagation. */
class ReviewOptimizationArchitectureTest {
    @Test
    void runtimePolicyCopiesHaveASmallCanonicalConstructorAndStagesCannotCaptureAnOuterService() throws Exception {
        assertThat(ReviewPolicySettings.class.getRecordComponents()).hasSize(8);
        for (Class<?> stage : Class.forName("com.repoguard.agent.review.LlmReviewPipeline").getDeclaredClasses()) {
            assertThat(Arrays.stream(stage.getDeclaredFields()).anyMatch(field -> field.getName().startsWith("this$")))
                .as("implicit outer service capture in %s", stage.getSimpleName()).isFalse();
            assertThat(stage.getRecordComponents()).as(stage.getSimpleName()).hasSizeLessThanOrEqualTo(9);
        }
    }

    @Test
    void reviewHotspotsReportBranchesConstructorsAndChangePropagation() throws Exception {
        Path root = Path.of("src/main/java/com/repoguard/agent");
        List<Path> targets = List.of(root.resolve("review/LlmReviewPipeline.java"),
            root.resolve("review/task/ReviewTaskTransitionStore.java"),
            root.resolve("review/progress/ReviewProgressStreamService.java"),
            root.resolve("review/quality/LlmModelReleaseService.java"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var files = compiler.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8)) {
            var task = (JavacTask) compiler.getTask(null, files, null, List.of("-proc:none"), null,
                files.getJavaFileObjectsFromPaths(targets));
            for (CompilationUnitTree unit : task.parse()) {
                Map<String, Integer> branches = new TreeMap<>();
                int[] constructorMax = {0};
                new TreeScanner<Void, Void>() {
                    @Override public Void visitMethod(MethodTree method, Void ignored) {
                        if (method.getName().contentEquals("<init>")) constructorMax[0] = Math.max(constructorMax[0], method.getParameters().size());
                        int[] decisions = {0};
                        new TreeScanner<Void, Void>() {
                            @Override public Void scan(Tree node, Void value) {
                                if (node != null && Set.of(Tree.Kind.IF, Tree.Kind.CONDITIONAL_EXPRESSION,
                                    Tree.Kind.CASE, Tree.Kind.FOR_LOOP, Tree.Kind.ENHANCED_FOR_LOOP,
                                    Tree.Kind.WHILE_LOOP, Tree.Kind.DO_WHILE_LOOP, Tree.Kind.CATCH,
                                    Tree.Kind.CONDITIONAL_AND, Tree.Kind.CONDITIONAL_OR).contains(node.getKind())) decisions[0]++;
                                return super.scan(node, value);
                            }
                        }.scan(method.getBody(), null);
                        branches.merge(method.getName().toString(), decisions[0], Math::max);
                        return super.visitMethod(method, ignored);
                    }
                }.scan(unit, null);
                int max = branches.values().stream().mapToInt(Integer::intValue).max().orElse(0);
                System.out.println("CODE_OPT_METRIC branches " + unit.getSourceFile().getName() + " " + branches);
                String file = unit.getSourceFile().getName();
                int branchBudget = file.contains("ReviewProgressStreamService") ? 16 : 8;
                int constructorBudget = file.contains("LlmReviewPipeline") ? 12 : file.contains("ReviewTaskTransitionStore") ? 2
                    : file.contains("ReviewProgressStreamService") ? 7 : 6;
                System.out.println("CODE_OPT_METRIC maximum constructor parameters " + file + "=" + constructorMax[0]);
                assertThat(max).as("method decision count in %s", file).isLessThanOrEqualTo(branchBudget);
                assertThat(constructorMax[0]).as("constructor parameters in %s", file).isLessThanOrEqualTo(constructorBudget);
            }
        }
        try (var paths = Files.walk(root)) {
            long consumers = paths.filter(path -> path.toString().endsWith(".java")).filter(path -> {
                try { return Files.readString(path).contains("import com.repoguard.agent.review.ReviewPolicySettings;"); }
                catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
            }).count();
            System.out.println("CODE_OPT_METRIC ReviewPolicySettings importing source files=" + consumers);
            assertThat(consumers).as("policy change propagation").isLessThanOrEqualTo(12);
        }
    }
}
