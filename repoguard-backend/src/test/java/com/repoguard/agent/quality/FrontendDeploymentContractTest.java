package com.repoguard.agent.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class FrontendDeploymentContractTest {

    private static final Pattern NGINX_LISTEN_PORT = Pattern.compile("(?m)^\\s*listen\\s+(\\d+)\\b");
    private static final Pattern DOCKER_EXPOSE_PORT = Pattern.compile("(?m)^EXPOSE\\s+(\\d+)\\s*$");

    @Test
    void ipComposePublishesThePortUsedByFrontendImageAndNginx() throws IOException {
        Path repositoryRoot = findRepositoryRoot();
        Map<String, Object> compose = yaml(repositoryRoot.resolve("docker-compose.ip.yml"));
        Map<String, Object> services = map(compose.get("services"));
        Map<String, Object> frontend = map(services.get("frontend"));
        List<String> publishedPorts = stringList(frontend.get("ports"));
        String nginx = read(repositoryRoot.resolve("repoguard-frontend/nginx.ip.conf"));
        String dockerfile = read(repositoryRoot.resolve("repoguard-frontend/Dockerfile"));
        String listenPort = requiredGroup(NGINX_LISTEN_PORT, nginx, "frontend Nginx listen port");
        String exposedPort = requiredGroup(DOCKER_EXPOSE_PORT, dockerfile, "frontend Docker exposed port");

        assertThat(exposedPort).isEqualTo(listenPort);
        assertThat(publishedPorts).containsExactly("80:" + listenPort);
    }

    @Test
    void nginxDoesNotExposeObservabilityServices() throws IOException {
        Path repositoryRoot = findRepositoryRoot();
        String nginx = read(repositoryRoot.resolve("repoguard-frontend/nginx.ip.conf"));

        assertThat(nginx)
            .doesNotContain("/grafana", "http://grafana:3000", "http://loki", "http://alloy");
    }

    @Test
    void frontendImageBakesItsConfigurationForReadOnlyRuntime() throws IOException {
        Path repositoryRoot = findRepositoryRoot();
        String dockerfile = read(repositoryRoot.resolve("repoguard-frontend/Dockerfile"));
        String nginx = read(repositoryRoot.resolve("repoguard-frontend/nginx.ip.conf"));
        Map<String, Object> production =
            yaml(repositoryRoot.resolve("docker-compose.prod.yml"));
        Map<String, Object> ipDeployment =
            yaml(repositoryRoot.resolve("docker-compose.ip.yml"));
        Map<String, Object> productionFrontend =
            map(map(production.get("services")).get("frontend"));
        Map<String, Object> ipFrontend =
            map(map(ipDeployment.get("services")).get("frontend"));

        assertThat(dockerfile)
            .contains("COPY nginx.ip.conf /etc/nginx/conf.d/default.conf")
            .doesNotContain("/etc/nginx/templates");
        assertThat(nginx)
            .contains("server_name _;")
            .doesNotContain("${REPOGUARD_FRONTEND_SERVER_NAME}");
        assertThat(stringList(productionFrontend.get("tmpfs")))
            .containsExactly("/tmp:rw,noexec,nosuid,size=16m,mode=1777")
            .noneMatch(this::shadowsFrontendImageDirectory);
        assertThat(stringList(ipFrontend.get("tmpfs")))
            .containsExactly("/tmp:rw,noexec,nosuid,size=16m,mode=1777")
            .noneMatch(this::shadowsFrontendImageDirectory);
        assertThat(productionFrontend).doesNotContainKey("environment");
        assertThat(read(repositoryRoot.resolve("repoguard-frontend/nginx.conf")))
            .contains(
                "client_body_temp_path /tmp/client_temp;",
                "proxy_temp_path /tmp/proxy_temp;",
                "fastcgi_temp_path /tmp/fastcgi_temp;",
                "uwsgi_temp_path /tmp/uwsgi_temp;",
                "scgi_temp_path /tmp/scgi_temp;"
            )
            .doesNotContain("_temp_path /var/cache/nginx");
    }

    @Test
    void productionCaddyOnlyProxiesPlainHttpHealthChecks() throws IOException {
        Path repositoryRoot = findRepositoryRoot();
        String caddyfile = read(repositoryRoot.resolve("Caddyfile"));
        String plainHttpCatchAll = requiredSiteBlock(caddyfile, ":80");
        String normalized = plainHttpCatchAll.replaceAll("\\s+", " ").trim();

        assertThat(normalized).contains(
            "@health path /actuator/health handle @health { reverse_proxy frontend:8080 } "
                + "handle { respond \"HTTPS canonical host required\" 421 }"
        );
        assertThat(occurrences(plainHttpCatchAll, "reverse_proxy frontend:8080")).isEqualTo(1);
    }

    @Test
    void productionComposeRequiresCanonicalHostAndEnablesSecureCookies() throws IOException {
        Path repositoryRoot = findRepositoryRoot();
        Map<String, Object> compose = yaml(repositoryRoot.resolve("docker-compose.prod.yml"));
        Map<String, Object> services = map(compose.get("services"));
        Map<String, Object> backendEnvironment = map(map(services.get("backend")).get("environment"));
        Map<String, Object> workerEnvironment = map(map(services.get("backend-worker")).get("environment"));
        Map<String, Object> frontend = map(services.get("frontend"));
        Map<String, Object> caddyEnvironment = map(map(services.get("caddy")).get("environment"));

        assertThat(backendEnvironment.get("REPOGUARD_AUTH_SECURE_COOKIES"))
            .isEqualTo("${REPOGUARD_AUTH_SECURE_COOKIES:-true}");
        assertThat(workerEnvironment.get("REPOGUARD_AUTH_SECURE_COOKIES"))
            .isEqualTo("${REPOGUARD_AUTH_SECURE_COOKIES:-true}");
        assertThat(backendEnvironment.get("REPOGUARD_AUTH_REFRESH_CONCURRENCY_GRACE_SECONDS"))
            .isEqualTo("${REPOGUARD_AUTH_REFRESH_CONCURRENCY_GRACE_SECONDS:-5}");
        assertThat(workerEnvironment.get("REPOGUARD_AUTH_REFRESH_CONCURRENCY_GRACE_SECONDS"))
            .isEqualTo("${REPOGUARD_AUTH_REFRESH_CONCURRENCY_GRACE_SECONDS:-5}");
        assertThat(frontend).doesNotContainKey("environment");
        assertThat(caddyEnvironment.get("REPOGUARD_FRONTEND_SERVER_NAME"))
            .isEqualTo("${REPOGUARD_FRONTEND_SERVER_NAME:?REPOGUARD_FRONTEND_SERVER_NAME is required}");
    }

    @Test
    void releaseRunsFrontendUnderProductionSecurityConstraints() throws IOException {
        Path repositoryRoot = findRepositoryRoot();
        String workflow = read(repositoryRoot.resolve(".github/workflows/release-images.yml"));
        String runtimeProbe = workflow.substring(
            workflow.indexOf("- name: Verify production frontend runtime health"),
            workflow.indexOf("- name: Scan backend image for high and critical CVEs")
        );

        assertThat(runtimeProbe)
            .contains(
                "${ALIYUN_FRONTEND_IMAGE}@${{ steps.frontend_image.outputs.digest }}",
                "--read-only",
                "--cap-drop ALL",
                "--security-opt no-new-privileges:true",
                "--tmpfs /tmp:rw,noexec,nosuid,size=16m,mode=1777",
                "test \"$(docker exec \"$container\" id -u)\" != \"0\"",
                "http://127.0.0.1:8080/healthz",
                "Frontend runtime container exited before becoming healthy."
            )
            .doesNotContain("--tmpfs /etc/nginx/conf.d", "--tmpfs /var/cache/nginx");
    }

    @Test
    void productionComposeUsesExclusiveRuntimeRolesAndFencesApiScaleOut() throws IOException {
        Path repositoryRoot = findRepositoryRoot();
        Map<String, Object> compose = yaml(repositoryRoot.resolve("docker-compose.prod.yml"));
        Map<String, Object> services = map(compose.get("services"));
        Map<String, Object> backendEnvironment = map(map(services.get("backend")).get("environment"));
        Map<String, Object> workerEnvironment = map(map(services.get("backend-worker")).get("environment"));
        String deployScript = read(repositoryRoot.resolve("scripts/deploy-prod.sh"));

        assertThat(backendEnvironment)
            .containsEntry("REPOGUARD_RUNTIME_ROLE", "${REPOGUARD_RUNTIME_ROLE:-combined}")
            .containsEntry("REPOGUARD_DEPLOYMENT_MODE", "${REPOGUARD_DEPLOYMENT_MODE:-monolith}")
            .containsEntry("REPOGUARD_API_INSTANCE_COUNT", "${REPOGUARD_API_INSTANCE_COUNT:-1}")
            .containsEntry("REPOGUARD_RATE_LIMIT_STORE", "${REPOGUARD_RATE_LIMIT_STORE:-local}")
            .doesNotContainKeys("REPOGUARD_API_ENABLED", "REPOGUARD_WORKER_ENABLED");
        assertThat(workerEnvironment)
            .containsEntry("REPOGUARD_RUNTIME_ROLE", "worker")
            .containsEntry("REPOGUARD_DEPLOYMENT_MODE", "${REPOGUARD_DEPLOYMENT_MODE:-monolith}")
            .containsEntry("REPOGUARD_API_INSTANCE_COUNT", 0)
            .containsEntry("REPOGUARD_RATE_LIMIT_STORE", "${REPOGUARD_RATE_LIMIT_STORE:-local}")
            .doesNotContainKeys("REPOGUARD_API_ENABLED", "REPOGUARD_WORKER_ENABLED");
        assertThat(deployScript)
            .contains("Split deployment requires REPOGUARD_RUNTIME_ROLE=api")
            .contains("Monolithic deployment requires REPOGUARD_RUNTIME_ROLE=combined")
            .contains("Compose services require REPOGUARD_DEPLOYMENT_MODE=$expected_deployment_mode")
            .contains("REPOGUARD_API_INSTANCE_COUNT greater than 1 requires REPOGUARD_RATE_LIMIT_STORE=database")
            .contains("REPOGUARD_WORKER_ENABLED is deprecated");
    }

    @Test
    void productionDeployScriptHoldsAnExclusiveDeploymentLock() throws IOException {
        Path repositoryRoot = findRepositoryRoot();
        String deployScript = read(repositoryRoot.resolve("scripts/deploy-prod.sh"));

        assertThat(deployScript)
            .contains("DEPLOY_LOCK_FILE=\"${DEPLOY_LOCK_FILE:-.deploy.lock}\"")
            .contains("exec 9>\"$DEPLOY_LOCK_FILE\"")
            .contains("flock -n 9")
            .contains("refusing to run concurrently");
        assertThat(deployScript.indexOf("flock -n 9")).isLessThan(deployScript.indexOf("compose pull"));
    }

    @Test
    void applicationComposeStacksRotateContainerLogs() throws IOException {
        Path repositoryRoot = findRepositoryRoot();
        Map<String, Object> prodServices = map(yaml(repositoryRoot.resolve("docker-compose.prod.yml")).get("services"));
        Map<String, Object> ipServices = map(yaml(repositoryRoot.resolve("docker-compose.ip.yml")).get("services"));

        assertThat(prodServices.keySet())
            .containsExactlyInAnyOrder("mysql", "rabbitmq", "backend", "backend-worker", "frontend", "caddy");
        assertThat(ipServices.keySet()).containsExactlyInAnyOrder("mysql", "rabbitmq", "backend", "frontend");
        prodServices.forEach((name, service) -> assertRotatedLogging(name, map(service)));
        ipServices.forEach((name, service) -> assertRotatedLogging(name, map(service)));
    }

    @Test
    void grafanaIsLoopbackOnlyAndHasNoApplicationBridge() throws IOException {
        Path repositoryRoot = findRepositoryRoot();
        Map<String, Object> observability = yaml(repositoryRoot.resolve("docker-compose.observability.yml"));
        Map<String, Object> grafana = map(map(observability.get("services")).get("grafana"));
        Map<String, Object> grafanaEnvironment = map(grafana.get("environment"));
        Map<String, Object> prodCompose = yaml(repositoryRoot.resolve("docker-compose.prod.yml"));

        assertThat(Files.exists(repositoryRoot.resolve("docker-compose.grafana-bridge.yml"))).isFalse();
        assertThat(stringList(grafana.get("ports"))).containsExactly("127.0.0.1:3000:3000");
        assertThat(grafanaEnvironment)
            .containsEntry("GF_SERVER_ROOT_URL", "http://localhost:3000/")
            .containsEntry("GF_SERVER_SERVE_FROM_SUB_PATH", "false");
        assertThat(prodCompose).doesNotContainKey("networks");
    }

    private void assertRotatedLogging(String serviceName, Map<String, Object> service) {
        Map<String, Object> logging = map(service.get("logging"));
        assertThat(logging).as("logging for " + serviceName).isNotNull();
        Map<String, Object> options = map(logging.get("options"));
        boolean backendService = serviceName.startsWith("backend");
        assertThat(logging.get("driver")).as("logging driver for " + serviceName).isEqualTo("json-file");
        assertThat(options.get("max-size")).as("max-size for " + serviceName).isEqualTo(backendService ? "50m" : "10m");
        assertThat(options.get("max-file")).as("max-file for " + serviceName).isEqualTo(backendService ? "5" : "3");
    }

    private boolean shadowsFrontendImageDirectory(String mount) {
        return mount.startsWith("/etc/nginx/conf.d")
            || mount.startsWith("/var/cache/nginx");
    }

    private Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve(".git")) && Files.isDirectory(current.resolve("repoguard-frontend"))) {
                return current;
            }
            current = current.getParent();
        }
        fail("Cannot locate repository root from " + Path.of("").toAbsolutePath());
        throw new IllegalStateException("unreachable");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> yaml(Path path) throws IOException {
        return (Map<String, Object>) new Yaml().load(read(path));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        return (List<String>) value;
    }

    private String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private String requiredGroup(Pattern pattern, String source, String description) {
        Matcher matcher = pattern.matcher(source);
        assertThat(matcher.find()).as(description).isTrue();
        return matcher.group(1);
    }

    private String requiredSiteBlock(String caddyfile, String siteAddress) {
        Pattern openingLine = Pattern.compile("(?m)^" + Pattern.quote(siteAddress) + "\\s*\\{\\s*$");
        Matcher matcher = openingLine.matcher(caddyfile);
        assertThat(matcher.find()).as("Caddy site block for " + siteAddress).isTrue();
        int openingBrace = caddyfile.indexOf('{', matcher.start());
        int depth = 0;
        for (int index = openingBrace; index < caddyfile.length(); index++) {
            char current = caddyfile.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return caddyfile.substring(openingBrace + 1, index);
            }
        }
        fail("Unclosed Caddy site block for " + siteAddress);
        throw new IllegalStateException("unreachable");
    }

    private int occurrences(String source, String expected) {
        return source.split(Pattern.quote(expected), -1).length - 1;
    }
}
