package com.repoguard.agent.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

class ApplicationArchitectureTest {

    private static final String BASE_PACKAGE = "com.repoguard.agent";
    private static final String AUTHENTICATION_PACKAGE = BASE_PACKAGE + ".authentication";
    private static final String CONTROLLER_PACKAGE = BASE_PACKAGE + ".controller";
    private static final String DTO_PACKAGE = BASE_PACKAGE + ".dto";
    private static final String ENTITY_PACKAGE = BASE_PACKAGE + ".entity";
    private static final String IDENTITY_PACKAGE = BASE_PACKAGE + ".identity";
    private static final String IDENTITY_INTERNAL_PACKAGE = IDENTITY_PACKAGE + ".internal";
    private static final String MAPPER_PACKAGE = BASE_PACKAGE + ".mapper";
    private static final String SECURITY_PACKAGE = BASE_PACKAGE + ".security";
    private static final String USER_PACKAGE = BASE_PACKAGE + ".user";
    private static final String USER_INTERNAL_PACKAGE = USER_PACKAGE + ".internal";
    private static final String WEB_PACKAGE = BASE_PACKAGE + ".web";
    private static final Path MAIN_SOURCE_ROOT = Path.of("src", "main", "java").toAbsolutePath().normalize();
    private static final Set<String> TECHNICAL_PACKAGE_ROOTS = Set.of(
        "common",
        "concurrency",
        "config",
        "controller",
        "dto",
        "entity",
        "mapper",
        "service"
    );

    // Existing cyclic edges are reviewed architecture debt. Removing an entry is safe; adding one is not.
    private static final Set<String> REVIEWED_CYCLIC_DEPENDENCY_BASELINE = Set.of(
        "external->observability",
        "github->external",
        "github->observability",
        "github->review",
        "messaging->observability",
        "messaging->review",
        "notification->external",
        "notification->messaging",
        "observability->external",
        "observability->messaging",
        "observability->worker",
        "review->external",
        "review->github",
        "review->observability",
        "worker->external",
        "worker->github",
        "worker->messaging",
        "worker->notification",
        "worker->observability",
        "worker->review"
    );

    private static final List<SourceUnit> SOURCES = loadSourceUnits();

    @Test
    void controllersDoNotDependDirectlyOnPersistenceLayer() {
        List<String> controllers = SOURCES.stream()
            .filter(source -> isInPackage(source.packageName(), CONTROLLER_PACKAGE))
            .map(SourceUnit::path)
            .toList();
        List<String> violations = SOURCES.stream()
            .filter(source -> isInPackage(source.packageName(), CONTROLLER_PACKAGE))
            .flatMap(source -> source.dependencies().stream()
                .filter(dependency -> isInPackage(dependency, MAPPER_PACKAGE)
                    || isInPackage(dependency, ENTITY_PACKAGE))
                .map(dependency -> source.path() + " -> " + dependency))
            .distinct()
            .sorted()
            .toList();

        assertThat(controllers).as("controller source discovery").isNotEmpty();
        assertThat(violations)
            .as("Controllers must call application services instead of persistence types")
            .isEmpty();
    }

    @Test
    void authenticationContractHasNoProjectImplementationDependencies() {
        List<String> authenticationSources = SOURCES.stream()
            .filter(source -> isInPackage(source.packageName(), AUTHENTICATION_PACKAGE))
            .map(SourceUnit::path)
            .toList();
        List<String> violations = SOURCES.stream()
            .filter(source -> isInPackage(source.packageName(), AUTHENTICATION_PACKAGE))
            .flatMap(source -> source.dependencies().stream()
                .filter(dependency -> dependency.startsWith(BASE_PACKAGE + ".")
                    && !isInPackage(dependency, AUTHENTICATION_PACKAGE))
                .map(dependency -> source.path() + " -> " + dependency))
            .distinct()
            .sorted()
            .toList();

        assertThat(authenticationSources).as("authentication contract source discovery").isNotEmpty();
        assertThat(violations)
            .as("Authenticated request values must stay independent of project implementations")
            .isEmpty();
    }

    @Test
    void webAdaptersDoNotDependOnSecurityTokenImplementations() {
        Set<String> forbiddenTypes = Set.of(
            SECURITY_PACKAGE + ".AuthTokenFilter",
            SECURITY_PACKAGE + ".AuthTokenService"
        );
        List<String> violations = SOURCES.stream()
            .filter(source -> isInPackage(source.packageName(), CONTROLLER_PACKAGE)
                || isInPackage(source.packageName(), WEB_PACKAGE))
            .flatMap(source -> source.dependencies().stream()
                .filter(dependency -> forbiddenTypes.stream()
                    .anyMatch(type -> dependency.equals(type) || dependency.startsWith(type + ".")))
                .map(dependency -> source.path() + " -> " + dependency))
            .distinct()
            .sorted()
            .toList();

        assertThat(violations)
            .as("HTTP adapters consume the neutral principal contract, never token implementation types")
            .isEmpty();
    }

    @Test
    void userManagementControllerUsesTheUserApplicationPortDirectly() {
        SourceUnit controller = SOURCES.stream()
            .filter(source -> source.path().endsWith("controller/UserManagementController.java"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("UserManagementController source was not discovered"));

        assertThat(controller.dependencies())
            .contains(USER_PACKAGE + ".UserManagementLifecycle")
            .noneMatch(dependency -> dependency.equals(BASE_PACKAGE + ".service.UserManagementService")
                || dependency.startsWith(BASE_PACKAGE + ".service.impl.UserManagementServiceImpl"));
    }

    @Test
    void identityInternalsArePrivateToTheIdentityBoundary() {
        List<String> identitySources = SOURCES.stream()
            .filter(source -> isInPackage(source.packageName(), IDENTITY_PACKAGE))
            .map(SourceUnit::path)
            .toList();
        List<String> violations = SOURCES.stream()
            .filter(source -> !isInPackage(source.packageName(), IDENTITY_PACKAGE))
            .flatMap(source -> source.dependencies().stream()
                .filter(dependency -> isInPackage(dependency, IDENTITY_INTERNAL_PACKAGE))
                .map(dependency -> source.path() + " -> " + dependency))
            .distinct()
            .sorted()
            .toList();

        assertThat(identitySources).as("identity source discovery").isNotEmpty();
        assertThat(violations)
            .as("Other domains may depend on identity application ports, never identity internals")
            .isEmpty();
    }

    @Test
    void identityPublicApiDoesNotExposePersistenceTypes() {
        List<String> publicIdentitySources = SOURCES.stream()
            .filter(source -> source.packageName().equals(IDENTITY_PACKAGE))
            .map(SourceUnit::path)
            .toList();
        List<String> violations = SOURCES.stream()
            .filter(source -> source.packageName().equals(IDENTITY_PACKAGE))
            .flatMap(source -> source.dependencies().stream()
                .filter(dependency -> isInPackage(dependency, ENTITY_PACKAGE)
                    || isInPackage(dependency, MAPPER_PACKAGE))
                .map(dependency -> source.path() + " -> " + dependency))
            .distinct()
            .sorted()
            .toList();

        assertThat(publicIdentitySources).as("public identity API source discovery").isNotEmpty();
        assertThat(violations)
            .as("Identity application ports must expose identity-owned values, never persistence types")
            .isEmpty();
    }

    @Test
    void identityBoundaryDoesNotDependOnUserImplementationPackage() {
        List<String> violations = SOURCES.stream()
            .filter(source -> isInPackage(source.packageName(), IDENTITY_PACKAGE))
            .flatMap(source -> source.dependencies().stream()
                .filter(dependency -> isInPackage(dependency, USER_PACKAGE))
                .map(dependency -> source.path() + " -> " + dependency))
            .distinct()
            .sorted()
            .toList();

        assertThat(violations)
            .as("Identity owns authentication and session behavior and must not depend on user implementations")
            .isEmpty();
    }

    @Test
    void userInternalsArePrivateToTheUserBoundary() {
        List<String> userSources = SOURCES.stream()
            .filter(source -> isInPackage(source.packageName(), USER_PACKAGE))
            .map(SourceUnit::path)
            .toList();
        List<String> violations = SOURCES.stream()
            .filter(source -> !isInPackage(source.packageName(), USER_PACKAGE))
            .flatMap(source -> source.dependencies().stream()
                .filter(dependency -> isInPackage(dependency, USER_INTERNAL_PACKAGE))
                .map(dependency -> source.path() + " -> " + dependency))
            .distinct()
            .sorted()
            .toList();

        assertThat(userSources).as("user source discovery").isNotEmpty();
        assertThat(violations)
            .as("Other boundaries may depend on user application ports, never user internals")
            .isEmpty();
    }

    @Test
    void userPublicApiDoesNotExposeTechnicalOrPersistenceTypes() {
        List<String> publicUserSources = SOURCES.stream()
            .filter(source -> source.packageName().equals(USER_PACKAGE))
            .map(SourceUnit::path)
            .toList();
        List<String> violations = SOURCES.stream()
            .filter(source -> source.packageName().equals(USER_PACKAGE))
            .flatMap(source -> source.dependencies().stream()
                .filter(dependency -> isInPackage(dependency, DTO_PACKAGE)
                    || isInPackage(dependency, ENTITY_PACKAGE)
                    || isInPackage(dependency, MAPPER_PACKAGE)
                    || isInPackage(dependency, SECURITY_PACKAGE)
                    || isInPackage(dependency, WEB_PACKAGE))
                .map(dependency -> source.path() + " -> " + dependency))
            .distinct()
            .sorted()
            .toList();

        assertThat(publicUserSources).as("public user API source discovery").isNotEmpty();
        assertThat(violations)
            .as("User application ports must expose user-owned values without technical dependencies")
            .isEmpty();
    }

    @Test
    void userBoundaryDoesNotDependOnSecurityOrWebImplementations() {
        List<String> violations = SOURCES.stream()
            .filter(source -> isInPackage(source.packageName(), USER_PACKAGE))
            .flatMap(source -> source.dependencies().stream()
                .filter(dependency -> isInPackage(dependency, SECURITY_PACKAGE)
                    || isInPackage(dependency, WEB_PACKAGE))
                .map(dependency -> source.path() + " -> " + dependency))
            .distinct()
            .sorted()
            .toList();

        assertThat(violations)
            .as("User management depends on neutral or identity ports, never security or web implementations")
            .isEmpty();
    }

    @Test
    void implementationPackagesAreNotImportedAcrossBoundaries() {
        List<String> violations = SOURCES.stream()
            .flatMap(source -> source.dependencies().stream()
                .map(ApplicationArchitectureTest::implementationPackage)
                .filter(target -> target != null && !isInPackage(source.packageName(), target))
                .map(target -> source.path() + " [" + source.packageName() + "] -> " + target))
            .distinct()
            .sorted()
            .toList();

        assertThat(violations)
            .as("Implementation packages are private to their own package boundary")
            .isEmpty();
    }

    @Test
    void domainPackageCyclesDoNotExceedReviewedBaseline() {
        Map<String, Set<String>> dependencies = domainDependencies();
        Set<String> cyclicEdges = new TreeSet<>();
        dependencies.forEach((source, targets) -> targets.stream()
            .filter(target -> pathExists(dependencies, target, source))
            .map(target -> source + "->" + target)
            .forEach(cyclicEdges::add));

        Set<String> unexpectedCycles = new TreeSet<>(cyclicEdges);
        unexpectedCycles.removeAll(REVIEWED_CYCLIC_DEPENDENCY_BASELINE);

        assertThat(dependencies.keySet())
            .as("domain package discovery")
            .contains("dashboard", "identity", "notification", "observability", "retention", "review", "worker");
        assertThat(unexpectedCycles)
            .as("New cyclic domain dependency edges are forbidden; break the dependency or document a migration first")
            .isEmpty();
    }

    private static Map<String, Set<String>> domainDependencies() {
        Set<String> domainRoots = new TreeSet<>();
        SOURCES.stream()
            .map(SourceUnit::packageName)
            .map(ApplicationArchitectureTest::topLevelPackage)
            .filter(root -> root != null && !TECHNICAL_PACKAGE_ROOTS.contains(root))
            .forEach(domainRoots::add);

        Map<String, Set<String>> dependencies = new TreeMap<>();
        domainRoots.forEach(root -> dependencies.put(root, new TreeSet<>()));
        for (SourceUnit source : SOURCES) {
            String sourceRoot = topLevelPackage(source.packageName());
            if (sourceRoot == null || !domainRoots.contains(sourceRoot)) {
                continue;
            }
            source.dependencies().stream()
                .map(ApplicationArchitectureTest::topLevelPackage)
                .filter(target -> target != null && domainRoots.contains(target) && !sourceRoot.equals(target))
                .forEach(target -> dependencies.get(sourceRoot).add(target));
        }
        return dependencies;
    }

    private static boolean pathExists(Map<String, Set<String>> dependencies, String start, String target) {
        Deque<String> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (target.equals(current)) {
                return true;
            }
            pending.addAll(dependencies.getOrDefault(current, Set.of()));
        }
        return false;
    }

    private static String implementationPackage(String dependency) {
        if (!dependency.startsWith(BASE_PACKAGE + ".")) {
            return null;
        }
        String marker = ".impl";
        int markerIndex = dependency.indexOf(marker, BASE_PACKAGE.length());
        if (markerIndex < 0) {
            return null;
        }
        int endIndex = markerIndex + marker.length();
        if (endIndex < dependency.length() && dependency.charAt(endIndex) != '.') {
            return null;
        }
        return dependency.substring(0, endIndex);
    }

    private static String topLevelPackage(String qualifiedName) {
        String prefix = BASE_PACKAGE + ".";
        if (qualifiedName == null || !qualifiedName.startsWith(prefix)) {
            return null;
        }
        String remainder = qualifiedName.substring(prefix.length());
        int separator = remainder.indexOf('.');
        return separator < 0 ? remainder : remainder.substring(0, separator);
    }

    private static boolean isInPackage(String packageOrType, String expectedPackage) {
        return packageOrType.equals(expectedPackage) || packageOrType.startsWith(expectedPackage + ".");
    }

    private static List<SourceUnit> loadSourceUnits() {
        List<Path> sourcePaths;
        try (Stream<Path> paths = Files.walk(MAIN_SOURCE_ROOT)) {
            sourcePaths = paths
                .filter(path -> path.toString().endsWith(".java"))
                .sorted()
                .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot scan production Java sources", ex);
        }
        if (sourcePaths.isEmpty()) {
            throw new IllegalStateException("No production Java sources found under " + MAIN_SOURCE_ROOT);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("Architecture tests require a JDK with the system Java compiler");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
            diagnostics,
            null,
            StandardCharsets.UTF_8
        )) {
            Iterable<? extends JavaFileObject> javaFiles = fileManager.getJavaFileObjectsFromPaths(sourcePaths);
            JavacTask task = (JavacTask) compiler.getTask(
                null,
                fileManager,
                diagnostics,
                List.of("-proc:none"),
                null,
                javaFiles
            );
            List<SourceUnit> sources = new ArrayList<>();
            for (CompilationUnitTree unit : task.parse()) {
                String packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
                Set<String> dependencies = new TreeSet<>();
                unit.getImports().forEach(importTree -> dependencies.add(
                    importTree.getQualifiedIdentifier().toString().replace(".*", "")
                ));
                new TreeScanner<Void, Set<String>>() {
                    @Override
                    public Void visitMemberSelect(MemberSelectTree node, Set<String> values) {
                        String reference = node.toString();
                        if (reference.startsWith(BASE_PACKAGE + ".")) {
                            values.add(reference);
                        }
                        return super.visitMemberSelect(node, values);
                    }
                }.scan(unit, dependencies);
                Path sourcePath = Path.of(unit.getSourceFile().toUri()).toAbsolutePath().normalize();
                sources.add(new SourceUnit(
                    MAIN_SOURCE_ROOT.relativize(sourcePath).toString().replace('\\', '/'),
                    packageName,
                    Set.copyOf(dependencies)
                ));
            }
            List<Diagnostic<? extends JavaFileObject>> errors = diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .toList();
            if (!errors.isEmpty()) {
                throw new IllegalStateException("Cannot parse production Java sources: " + errors);
            }
            return List.copyOf(sources);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot close Java source parser", ex);
        }
    }

    private record SourceUnit(String path, String packageName, Set<String> dependencies) {}
}
