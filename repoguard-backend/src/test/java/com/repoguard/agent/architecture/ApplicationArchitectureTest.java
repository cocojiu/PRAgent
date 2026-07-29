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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static final String CONFIG_PACKAGE = BASE_PACKAGE + ".config";
    private static final String CONTROLLER_PACKAGE = BASE_PACKAGE + ".controller";
    private static final String DTO_PACKAGE = BASE_PACKAGE + ".dto";
    private static final String ENTITY_PACKAGE = BASE_PACKAGE + ".entity";
    private static final String IDENTITY_PACKAGE = BASE_PACKAGE + ".identity";
    private static final String IDENTITY_INTERNAL_PACKAGE = IDENTITY_PACKAGE + ".internal";
    private static final String MAPPER_PACKAGE = BASE_PACKAGE + ".mapper";
    private static final String SECURITY_PACKAGE = BASE_PACKAGE + ".security";
    private static final String SERVICE_IMPL_PACKAGE = BASE_PACKAGE + ".service.impl";
    private static final String USER_PACKAGE = BASE_PACKAGE + ".user";
    private static final String USER_INTERNAL_PACKAGE = USER_PACKAGE + ".internal";
    private static final String WEB_PACKAGE = BASE_PACKAGE + ".web";
    private static final Path MAIN_SOURCE_ROOT = Path.of("src", "main", "java").toAbsolutePath().normalize();
    private static final Pattern REVIEW_TASK_MAPPER_VARIABLE =
        Pattern.compile("\\bReviewTaskMapper\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\b");
    private static final Pattern TRANSACTIONAL_CACHE_EVICTION = Pattern.compile(
        "@Transactional(?:\\([^\\r\\n]*\\))?\\s*@CacheEvict"
            + "|@CacheEvict(?:\\([^\\r\\n]*\\))?\\s*@Transactional"
    );
    private static final Set<String> REVIEW_TASK_UPDATE_STORES = Set.of(
        "com/repoguard/agent/messaging/ReviewTaskPublishOutboxStore.java",
        "com/repoguard/agent/review/task/ReviewTaskTransitionStore.java",
        "com/repoguard/agent/worker/ReviewTaskClaimService.java"
    );
    private static final int SERVICE_IMPL_SOURCE_BASELINE = 36;
    private static final Set<String> TECHNICAL_PACKAGE_ROOTS = Set.of(
        "common",
        "concurrency",
        "cache",
        "config",
        "controller",
        "dto",
        "entity",
        "mapper",
        "service",
        "settings"
    );

    // Existing cyclic edges are reviewed architecture debt. Removing an entry is safe; adding one is not.
    private static final Set<String> REVIEWED_CYCLIC_DEPENDENCY_BASELINE = Set.of(
        "github->external",
        "github->observability",
        "github->review",
        "messaging->observability",
        "messaging->review",
        "notification->external",
        "notification->messaging",
        "observability->external",
        "observability->messaging",
        "review->external",
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
    void mappersReturnPersistenceOwnedTypesInsteadOfApiDtos() {
        List<String> mapperSources = SOURCES.stream()
            .filter(source -> isInPackage(source.packageName(), MAPPER_PACKAGE))
            .map(SourceUnit::path)
            .toList();
        List<String> violations = SOURCES.stream()
            .filter(source -> isInPackage(source.packageName(), MAPPER_PACKAGE))
            .flatMap(source -> source.dependencies().stream()
                .filter(dependency -> isInPackage(dependency, DTO_PACKAGE))
                .map(dependency -> source.path() + " -> " + dependency))
            .distinct()
            .sorted()
            .toList();

        assertThat(mapperSources).as("mapper source discovery").isNotEmpty();
        assertThat(violations)
            .as("Persistence mappers must expose mapper projections or entities, never API DTOs")
            .isEmpty();
    }

    @Test
    void serviceImplementationPackageCanOnlyShrink() {
        List<String> implementationSources = SOURCES.stream()
            .filter(source -> isInPackage(source.packageName(), SERVICE_IMPL_PACKAGE))
            .map(SourceUnit::path)
            .sorted()
            .toList();

        assertThat(implementationSources).as("service implementation source discovery").isNotEmpty();
        assertThat(implementationSources.size())
            .as("The service.impl migration baseline may only move down")
            .isLessThanOrEqualTo(SERVICE_IMPL_SOURCE_BASELINE);
    }

    @Test
    void transactionalWritesUseAfterCommitCacheEviction() {
        List<String> violations = SOURCES.stream()
            .filter(source -> TRANSACTIONAL_CACHE_EVICTION.matcher(source.sourceText()).find())
            .map(SourceUnit::path)
            .sorted()
            .toList();

        assertThat(violations)
            .as("Transactional writes must evict caches through an afterCommit boundary, never direct @CacheEvict")
            .isEmpty();
    }

    @Test
    void configPackageContainsOnlyConfigurationInfrastructure() {
        List<String> configSources = SOURCES.stream()
            .filter(source -> isInPackage(source.packageName(), CONFIG_PACKAGE))
            .map(SourceUnit::path)
            .toList();
        List<String> violations = SOURCES.stream()
            .filter(source -> isInPackage(source.packageName(), CONFIG_PACKAGE))
            .filter(source -> source.sourceText().contains("@Service")
                || source.sourceText().contains("@Repository")
                || source.path().matches(".*(?:Provider|Settings|Service|Mapper|Assembler)\\.java$"))
            .map(SourceUnit::path)
            .sorted()
            .toList();

        assertThat(configSources).as("configuration source discovery").isNotEmpty();
        assertThat(violations)
            .as("Business providers, settings and services must live in their owning domain")
            .isEmpty();
    }

    @Test
    void sqlVerificationPlansStayOutOfProductionBeans() {
        List<String> violations = SOURCES.stream()
            .map(SourceUnit::path)
            .filter(path -> path.endsWith("SqlVerificationPlan.java"))
            .sorted()
            .toList();

        assertThat(violations)
            .as("SQL verification plans are test infrastructure and must not ship in production")
            .isEmpty();
    }

    @Test
    void reviewTaskStateWritesStayInAdjudicatedStoresAndNeverUseUpdateById() {
        List<String> violations = new ArrayList<>();
        Set<String> storesWithWrites = new TreeSet<>();
        for (SourceUnit source : SOURCES) {
            Matcher variableMatcher = REVIEW_TASK_MAPPER_VARIABLE.matcher(source.sourceText());
            while (variableMatcher.find()) {
                String variableName = variableMatcher.group(1);
                Pattern updateCall = Pattern.compile(
                    "\\b" + Pattern.quote(variableName) + "\\s*\\.\\s*(updateById|update)\\s*\\("
                );
                Matcher updateMatcher = updateCall.matcher(source.sourceText());
                while (updateMatcher.find()) {
                    String method = updateMatcher.group(1);
                    if ("updateById".equals(method)) {
                        violations.add(source.path() + " -> " + variableName + ".updateById");
                    } else if (!REVIEW_TASK_UPDATE_STORES.contains(source.path())) {
                        violations.add(source.path() + " -> " + variableName + ".update");
                    } else {
                        storesWithWrites.add(source.path());
                    }
                }
            }
        }

        assertThat(violations)
            .as("ReviewTask updates must use conditional wrappers inside the adjudicated stores")
            .isEmpty();
        assertThat(storesWithWrites)
            .as("The ReviewTask update allowlist is a ratchet and must not contain stale entries")
            .containsExactlyInAnyOrderElementsOf(REVIEW_TASK_UPDATE_STORES);
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

    @Test
    void retiredCycleEdgesRemainAbsent() {
        Map<String, Set<String>> dependencies = domainDependencies();
        Set<String> retiredEdges = Set.of(
            "external->observability",
            "observability->worker",
            "review->github"
        );
        List<String> violations = retiredEdges.stream()
            .filter(edge -> {
                String[] packages = edge.split("->", 2);
                return dependencies.getOrDefault(packages[0], Set.of()).contains(packages[1]);
            })
            .sorted()
            .toList();

        assertThat(violations)
            .as("Retired architecture debt edges must not be reintroduced")
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
                    Set.copyOf(dependencies),
                    Files.readString(sourcePath, StandardCharsets.UTF_8)
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

    private record SourceUnit(
        String path,
        String packageName,
        Set<String> dependencies,
        String sourceText
    ) {}
}
