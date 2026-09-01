package com.repoguard.agent.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class EnterpriseKubernetesDeploymentContractTest {

    private static final int BACKEND_UID_GID = 10001;

    @Test
    void enterpriseWorkloadsAreReplicatedPinnedAndLeastPrivilege() throws IOException {
        List<Map<String, Object>> resources = resources();

        assertThat(map(resource(resources, "Namespace", "repoguard").get("metadata")))
            .extractingByKey("labels")
            .satisfies(labels -> assertThat(map(labels))
                .containsEntry("pod-security.kubernetes.io/enforce", "restricted"));
        assertDeployment(resources, "repoguard-api", 3, "api");
        assertDeployment(resources, "repoguard-worker", 2, "worker");
        assertDeployment(resources, "repoguard-frontend", 2, null);
        assertThat(resources.stream().filter(resource -> "PodDisruptionBudget".equals(resource.get("kind"))))
            .hasSize(3);
        assertThat(resources.stream().filter(resource -> "HorizontalPodAutoscaler".equals(resource.get("kind"))))
            .hasSize(2);
        assertThat(resourceNames(resources, "ResourceQuota"))
            .containsExactly("repoguard-compute-budget");
        assertThat(resourceNames(resources, "LimitRange"))
            .containsExactly("repoguard-container-defaults");
    }

    @Test
    void runtimeUsesExternalSecretsV78AndInternalBackendAlias() throws IOException {
        List<Map<String, Object>> resources = resources();
        Map<String, Object> config = map(resource(resources, "ConfigMap", "repoguard-runtime").get("data"));

        assertThat(config)
            .containsEntry("REPOGUARD_TENANCY_ENABLED", "true")
            .containsEntry("REPOGUARD_ENTERPRISE_OIDC_ENABLED", "true")
            .containsEntry("REPOGUARD_GITHUB_APP_ENABLED", "true")
            .containsEntry("REPOGUARD_SCHEMA_EXPECTED_VERSION", "78")
            .containsEntry("REPOGUARD_SCHEDULING_LEASE_SECONDS", "900")
            .containsEntry("REPOGUARD_SCHEDULING_HEARTBEAT_SECONDS", "60")
            .containsEntry("REPOGUARD_SCHEDULING_HEARTBEAT_THREADS", "2")
            .containsEntry("REPOGUARD_CACHE_INVALIDATION_ENABLED", "true")
            .containsEntry("REPOGUARD_CACHE_INVALIDATION_POLL_INTERVAL_MS", "1000")
            .containsEntry("REPOGUARD_CACHE_INVALIDATION_BATCH_SIZE", "200")
            .containsEntry("REPOGUARD_CACHE_INVALIDATION_MAX_BATCHES_PER_POLL", "10")
            .containsEntry("REPOGUARD_RATE_LIMIT_STORE", "database");
        assertThat(resources).noneMatch(resource -> "Secret".equals(resource.get("kind")));

        Map<String, Object> backendService = resource(resources, "Service", "backend");
        assertThat(map(map(backendService.get("spec")).get("selector")))
            .containsEntry("app.kubernetes.io/name", "repoguard-api");
        assertThat(resourceNames(resources, "NetworkPolicy"))
            .containsExactlyInAnyOrder(
                "repoguard-default-deny-ingress",
                "repoguard-default-deny-egress",
                "repoguard-allow-dns-egress",
                "repoguard-allow-frontend-egress",
                "repoguard-allow-api-egress",
                "repoguard-allow-worker-egress",
                "repoguard-allow-ingress-to-frontend",
                "repoguard-allow-frontend-to-api"
            );
        Map<String, Object> egressPolicy = resource(resources, "NetworkPolicy", "repoguard-default-deny-egress");
        assertThat(map(egressPolicy.get("spec")))
            .containsEntry("policyTypes", List.of("Egress"))
            .doesNotContainKey("egress");
    }

    @Test
    void workloadEgressIsScopedByIdentityDestinationAndPort() throws IOException {
        List<Map<String, Object>> resources = resources();

        Map<String, Object> dns = networkPolicySpec(resources, "repoguard-allow-dns-egress");
        assertThat(map(dns.get("podSelector"))).isEmpty();
        assertThat(egressRules(dns)).singleElement().satisfies(rule -> {
            assertThat(ports(rule)).containsExactlyInAnyOrder(53, 53);
            Map<String, Object> peer = peers(rule).getFirst();
            assertThat(matchLabels(peer, "namespaceSelector"))
                .containsExactlyEntriesOf(Map.of("kubernetes.io/metadata.name", "kube-system"));
            assertThat(matchLabels(peer, "podSelector"))
                .containsExactlyEntriesOf(Map.of("k8s-app", "kube-dns"));
        });

        Map<String, Object> frontend = networkPolicySpec(resources, "repoguard-allow-frontend-egress");
        assertWorkloadSelector(frontend, "repoguard-frontend");
        assertThat(egressRules(frontend)).singleElement().satisfies(rule -> {
            assertThat(ports(rule)).containsExactly(8081);
            assertThat(matchLabels(peers(rule).getFirst(), "podSelector"))
                .containsExactlyEntriesOf(Map.of("app.kubernetes.io/name", "repoguard-api"));
            assertThat(peers(rule).getFirst()).doesNotContainKeys("namespaceSelector", "ipBlock");
        });

        assertBackendEgress(resources, "repoguard-allow-api-egress", "repoguard-api");
        assertBackendEgress(resources, "repoguard-allow-worker-egress", "repoguard-worker");

        resourceNames(resources, "NetworkPolicy").stream()
            .filter(name -> name.contains("egress"))
            .filter(name -> !"repoguard-default-deny-egress".equals(name))
            .map(name -> networkPolicySpec(resources, name))
            .flatMap(spec -> egressRules(spec).stream())
            .forEach(rule -> assertThat(peers(rule))
                .as("every egress allow rule must name a destination")
                .isNotEmpty());
    }

    @Test
    void backendImageAndPodsShareStableSecretReadableIdentity() throws IOException {
        Path root = repositoryRoot();
        String dockerfile = Files.readString(
            root.resolve("repoguard-backend/Dockerfile"),
            StandardCharsets.UTF_8
        );

        assertThat(dockerfile)
            .contains("addgroup -S -g " + BACKEND_UID_GID + " repoguard")
            .contains("adduser -S -D -H -u " + BACKEND_UID_GID + " -G repoguard repoguard")
            .contains("USER " + BACKEND_UID_GID + ":" + BACKEND_UID_GID);

        List<Map<String, Object>> resources = resources();
        assertBackendPodIdentity(resources, "repoguard-api");
        assertBackendPodIdentity(resources, "repoguard-worker");
    }

    private void assertDeployment(
        List<Map<String, Object>> resources,
        String name,
        int replicas,
        String runtimeRole
    ) {
        Map<String, Object> deployment = resource(resources, "Deployment", name);
        Map<String, Object> spec = map(deployment.get("spec"));
        Map<String, Object> podSpec = map(map(map(spec.get("template")).get("spec")));
        Map<String, Object> container = map(list(podSpec.get("containers")).getFirst());
        Map<String, Object> security = map(container.get("securityContext"));

        assertThat(spec.get("replicas")).isEqualTo(replicas);
        assertThat(map(container.get("startupProbe")))
            .containsEntry("periodSeconds", runtimeRole == null ? 5 : 10)
            .containsEntry("failureThreshold", 30);
        assertThat(container.get("image").toString()).matches(".+@sha256:[0-9a-f]{64}");
        assertThat(security)
            .containsEntry("allowPrivilegeEscalation", false)
            .containsEntry("readOnlyRootFilesystem", true)
            .containsEntry("runAsNonRoot", true);
        assertThat(stringList(map(security.get("capabilities")).get("drop"))).containsExactly("ALL");
        assertThat(podSpec.get("automountServiceAccountToken")).isEqualTo(false);

        if ("worker".equals(runtimeRole)) {
            Map<String, Object> rollingUpdate = map(map(spec.get("strategy")).get("rollingUpdate"));
            assertThat(rollingUpdate.get("maxUnavailable")).isEqualTo(0);
        }

        if (runtimeRole != null) {
            assertThat(environment(container, "REPOGUARD_RUNTIME_ROLE")).isEqualTo(runtimeRole);
            assertThat(list(container.get("envFrom")).toString())
                .contains("repoguard-runtime", "repoguard-enterprise-env");
            assertThat(list(container.get("volumeMounts")).toString())
                .contains("/run/secrets");
        }
    }

    private void assertBackendPodIdentity(
        List<Map<String, Object>> resources,
        String deploymentName
    ) {
        Map<String, Object> deployment = resource(resources, "Deployment", deploymentName);
        Map<String, Object> podSpec = map(map(map(deployment.get("spec")).get("template")).get("spec"));
        Map<String, Object> podSecurity = map(podSpec.get("securityContext"));

        assertThat(podSecurity)
            .containsEntry("runAsNonRoot", true)
            .containsEntry("runAsUser", BACKEND_UID_GID)
            .containsEntry("runAsGroup", BACKEND_UID_GID)
            .containsEntry("fsGroup", BACKEND_UID_GID)
            .containsEntry("fsGroupChangePolicy", "OnRootMismatch");

        Map<String, Object> secretVolume = list(podSpec.get("volumes")).stream()
            .map(this::map)
            .filter(volume -> "secret-files".equals(volume.get("name")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing secret-files volume"));
        assertThat(map(secretVolume.get("secret"))).containsEntry("defaultMode", 0440);
    }

    private void assertBackendEgress(
        List<Map<String, Object>> resources,
        String policyName,
        String workloadName
    ) {
        Map<String, Object> spec = networkPolicySpec(resources, policyName);
        assertWorkloadSelector(spec, workloadName);
        List<Map<String, Object>> rules = egressRules(spec);
        assertThat(rules).hasSize(3);

        Map<String, Object> mysql = ruleForPort(rules, 3306);
        assertThat(ports(mysql)).containsExactly(3306);
        assertDependencyPeer(mysql, "networking.repoguard.io/mysql");

        Map<String, Object> rabbitMq = ruleForPort(rules, 5671);
        assertThat(ports(rabbitMq)).containsExactlyInAnyOrder(5671, 5672);
        assertDependencyPeer(rabbitMq, "networking.repoguard.io/rabbitmq");

        Map<String, Object> https = ruleForPort(rules, 443);
        assertThat(ports(https)).containsExactly(443);
        Map<String, Object> ipBlock = map(peers(https).getFirst().get("ipBlock"));
        assertThat(ipBlock).containsEntry("cidr", "0.0.0.0/0");
        assertThat(stringList(ipBlock.get("except")))
            .contains(
                "10.0.0.0/8",
                "127.0.0.0/8",
                "169.254.0.0/16",
                "172.16.0.0/12",
                "192.168.0.0/16"
            );
    }

    private void assertWorkloadSelector(Map<String, Object> spec, String workloadName) {
        assertThat(matchLabels(spec, "podSelector"))
            .containsExactlyEntriesOf(Map.of("app.kubernetes.io/name", workloadName));
    }

    private void assertDependencyPeer(Map<String, Object> rule, String label) {
        Map<String, Object> peer = peers(rule).getFirst();
        assertThat(matchLabels(peer, "namespaceSelector"))
            .containsExactlyEntriesOf(Map.of(label, "true"));
        assertThat(matchLabels(peer, "podSelector"))
            .containsExactlyEntriesOf(Map.of(label, "true"));
        assertThat(peer).doesNotContainKey("ipBlock");
    }

    private Map<String, Object> networkPolicySpec(
        List<Map<String, Object>> resources,
        String name
    ) {
        return map(resource(resources, "NetworkPolicy", name).get("spec"));
    }

    private List<Map<String, Object>> egressRules(Map<String, Object> spec) {
        return list(spec.get("egress")).stream().map(this::map).toList();
    }

    private List<Map<String, Object>> peers(Map<String, Object> rule) {
        return list(rule.get("to")).stream().map(this::map).toList();
    }

    private List<Integer> ports(Map<String, Object> rule) {
        return list(rule.get("ports")).stream()
            .map(this::map)
            .map(port -> (Integer) port.get("port"))
            .toList();
    }

    private Map<String, Object> ruleForPort(List<Map<String, Object>> rules, int port) {
        return rules.stream()
            .filter(rule -> ports(rule).contains(port))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing egress rule for TCP port " + port));
    }

    private Map<String, Object> matchLabels(Map<String, Object> parent, String selectorName) {
        return map(map(parent.get(selectorName)).get("matchLabels"));
    }

    private Object environment(Map<String, Object> container, String name) {
        return list(container.get("env")).stream()
            .map(this::map)
            .filter(value -> name.equals(value.get("name")))
            .map(value -> value.get("value"))
            .findFirst()
            .orElse(null);
    }

    private List<String> resourceNames(List<Map<String, Object>> resources, String kind) {
        return resources.stream()
            .filter(resource -> kind.equals(resource.get("kind")))
            .map(resource -> map(resource.get("metadata")).get("name").toString())
            .toList();
    }

    private Map<String, Object> resource(
        List<Map<String, Object>> resources,
        String kind,
        String name
    ) {
        return resources.stream()
            .filter(resource -> kind.equals(resource.get("kind")))
            .filter(resource -> name.equals(map(resource.get("metadata")).get("name")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing " + kind + "/" + name));
    }

    private List<Map<String, Object>> resources() throws IOException {
        Path root = repositoryRoot();
        String yaml = Files.readString(
            root.resolve("deploy/kubernetes/enterprise.yaml"),
            StandardCharsets.UTF_8
        );
        List<Map<String, Object>> values = new ArrayList<>();
        for (Object document : new Yaml().loadAll(yaml)) {
            values.add(map(document));
        }
        return values;
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve(".git"))
                && Files.isDirectory(current.resolve("deploy/kubernetes"))) {
                return current;
            }
            current = current.getParent();
        }
        fail("Cannot locate repository root");
        throw new IllegalStateException("unreachable");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(Object value) {
        return (List<Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        return (List<String>) value;
    }
}
