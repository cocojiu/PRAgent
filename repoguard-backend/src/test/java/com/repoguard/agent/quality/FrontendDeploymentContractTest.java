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
        Map<String, Object> frontendEnvironment = map(map(services.get("frontend")).get("environment"));
        Map<String, Object> caddyEnvironment = map(map(services.get("caddy")).get("environment"));

        assertThat(backendEnvironment.get("REPOGUARD_AUTH_SECURE_COOKIES"))
            .isEqualTo("${REPOGUARD_AUTH_SECURE_COOKIES:-true}");
        assertThat(workerEnvironment.get("REPOGUARD_AUTH_SECURE_COOKIES"))
            .isEqualTo("${REPOGUARD_AUTH_SECURE_COOKIES:-true}");
        assertThat(frontendEnvironment.get("REPOGUARD_FRONTEND_SERVER_NAME"))
            .isEqualTo("${REPOGUARD_FRONTEND_SERVER_NAME:?REPOGUARD_FRONTEND_SERVER_NAME is required}");
        assertThat(caddyEnvironment.get("REPOGUARD_FRONTEND_SERVER_NAME"))
            .isEqualTo("${REPOGUARD_FRONTEND_SERVER_NAME:?REPOGUARD_FRONTEND_SERVER_NAME is required}");
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
