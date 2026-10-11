package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.repoguard.agent.common.GlobalExceptionHandler;
import com.repoguard.agent.config.JacksonConfig;
import com.repoguard.agent.controller.PreflightController;
import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.github.GithubIntegrationProvider;
import com.repoguard.agent.github.GithubIntegrationSettings;
import com.repoguard.agent.mapper.PreflightPreviewMapper;
import com.repoguard.agent.mapper.UserAccountMapper;
import com.repoguard.agent.preflight.*;
import com.repoguard.agent.security.*;
import com.repoguard.agent.tenancy.*;
import com.repoguard.agent.web.AuditClientIpResolver;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.*;
import javax.sql.DataSource;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.*;

@EnabledIfEnvironmentVariable(named = "REPOGUARD_RUN_PREFLIGHT_INTEGRATION", matches = "true")
class PreflightTransportIntegrationTest {
    @TempDir Path temporary;
    private static DataSource data;
    private static JdbcTemplate jdbc;
    private static UserAccount user;
    private static final ObjectMapper JSON = new JacksonConfig().objectMapper();
    private static final AuthProperties AUTH = new AuthProperties();
    private static RuleBasedPullRequestReviewer ENGINE;
    private static final LlmPullRequestReviewer MODEL = mock(LlmPullRequestReviewer.class);
    private Tomcat tomcat;
    private AnnotationConfigWebApplicationContext context;

    @BeforeEach void setup() throws Exception {
        AUTH.setTokenSecret(UUID.randomUUID().toString());
        data = com.repoguard.agent.integration.PreflightIntegrationDatabase.migrateFixture();
        jdbc = new JdbcTemplate(data);
        String suffix = UUID.randomUUID().toString();
        jdbc.update("insert into user_account(username,email,password_hash,role,status,session_version,created_at,updated_at) values (?,?,?,'ADMIN','ACTIVE',0,now(),now())",
            "tls-" + suffix, suffix + "@fixture.invalid", suffix);
        user = new UserAccount(); user.setId(jdbc.queryForObject("select id from user_account where username=?", Long.class, "tls-" + suffix));
        user.setUsername("fixture"); user.setRole("ADMIN"); user.setStatus("ACTIVE"); user.setSessionVersion(0);
        String password = UUID.randomUUID().toString();
        run(temporary, Map.of("REPOGUARD_TLS_FIXTURE_PASS", password),
            Path.of(System.getProperty("java.home"), "bin", "keytool").toString(), "-genkeypair", "-alias", "fixture",
            "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12", "-keystore", temporary.resolve("fixture.p12").toString(),
            "-storepass:env", "REPOGUARD_TLS_FIXTURE_PASS", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "1", "-noprompt");
        run(temporary, Map.of("REPOGUARD_TLS_FIXTURE_PASS", password),
            Path.of(System.getProperty("java.home"), "bin", "keytool").toString(), "-exportcert", "-rfc", "-alias", "fixture",
            "-keystore", temporary.resolve("fixture.p12").toString(), "-storepass:env", "REPOGUARD_TLS_FIXTURE_PASS",
            "-file", temporary.resolve("fixture.pem").toString());
        tomcat = new Tomcat(); tomcat.setBaseDir(temporary.resolve("tomcat").toString()); tomcat.setPort(0);
        var connector = tomcat.getConnector(); connector.setProperty("address", "127.0.0.1");
        connector.setScheme("https"); connector.setSecure(true); connector.setProperty("SSLEnabled", "true");
        SSLHostConfig host = new SSLHostConfig();
        var certificate = new SSLHostConfigCertificate(host, SSLHostConfigCertificate.Type.RSA);
        certificate.setCertificateKeystoreFile(temporary.resolve("fixture.p12").toString());
        certificate.setCertificateKeystorePassword(password); certificate.setCertificateKeystoreType("PKCS12"); certificate.setCertificateKeyAlias("fixture");
        host.addCertificate(certificate); connector.addSslHostConfig(host);
        var servletContext = tomcat.addContext("", temporary.toString());
        context = new AnnotationConfigWebApplicationContext(); context.register(Fixture.class);
        context.setServletContext(servletContext.getServletContext()); context.refresh();
        var servlet = Tomcat.addServlet(servletContext, "preflight", new DispatcherServlet(context)); servlet.setLoadOnStartup(1);
        servletContext.addServletMapping("/", "preflight", false);
        var filter = new FilterDef(); filter.setFilterName("auth");
        filter.setFilter(new AuthTokenFilter(new AuthTokenService(AUTH),
            new AuthAccountCache(context.getBean(UserAccountMapper.class), false), JSON));
        servletContext.addFilterDef(filter);
        var mapping = new FilterMap(); mapping.setFilterName("auth"); mapping.addURLPattern("/*"); servletContext.addFilterMap(mapping);
        tomcat.start();
    }

    @AfterEach void cleanup() throws Exception {
        try { if (tomcat != null) { tomcat.stop(); tomcat.destroy(); } }
        finally {
            if (context != null) context.close();
            if (jdbc != null && user != null) {
                jdbc.update("delete from preflight_preview where user_id=?", user.getId());
                jdbc.update("delete from user_account where id=?", user.getId());
            }
        }
    }

    @Test void actualTlsCliRoundTripUsesScopedCredentialDedupAndFreshRevocation() throws Exception {
        long reviewsBefore = Objects.requireNonNull(jdbc.queryForObject("select count(*) from review_task", Long.class));
        var trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null, null);
        try (var input = Files.newInputStream(temporary.resolve("fixture.pem"))) {
            trust.setCertificateEntry("fixture", CertificateFactory.getInstance("X.509").generateCertificate(input));
        }
        var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); managers.init(trust);
        var tls = SSLContext.getInstance("TLS"); tls.init(null, managers.getTrustManagers(), null);
        try (var client = HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(5)).build()) {
            URI origin = URI.create("https://localhost:" + tomcat.getConnector().getLocalPort());
            String access = new AuthTokenService(AUTH).issueAccessToken(user).token();
            String shortToken = mint(client, origin, access);
            Path repo = temporary.resolve("git"); Files.createDirectories(repo);
            run(repo, Map.of(), "git", "init", "--quiet");
            run(repo, Map.of(), "git", "config", "user.name", "fixture");
            run(repo, Map.of(), "git", "config", "user.email", "fixture@fixture.invalid");
            Path file = repo.resolve("Example.java"); Files.writeString(file, "class Example {}\n");
            run(repo, Map.of(), "git", "add", "Example.java"); run(repo, Map.of(), "git", "commit", "--quiet", "-m", "fixture");
            Files.writeString(file, "class Example { void waitForIt() throws Exception { Thread.sleep(1); } }\n");
            String head = run(repo, Map.of(), "git", "rev-parse", "HEAD").strip();
            String patch = run(repo, Map.of(), "git", "diff", "--no-ext-diff", "--no-textconv",
                "--find-renames=50%", "-l200", "--unified=3", head, "--", "Example.java");
            var direct = ENGINE.review(new PullRequestDiff("fixture", "repo", 0, List.of(
                new PullRequestChangedFile("Example.java", "modified", 1, 1, patch))));
            assertThat(direct.findings().size()).as("Actual same-diff engine baseline").isEqualTo(1);
            clearInvocations(ENGINE);
            var payload = JSON.createObjectNode();
            payload.put("repository", "fixture/repo"); payload.put("baseSha", head);
            payload.put("mergeBaseSha", head); payload.put("headSha", head); payload.put("llmRequested", false);
            payload.putArray("files").addObject().put("filename", "Example.java").put("status", "modified").put("patch", patch);
            byte[] body = JSON.writeValueAsBytes(payload);
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            var preview = HttpRequest.newBuilder(origin.resolve("/api/v1/preflight/preview"))
                .header("Authorization", "Bearer " + shortToken).header("Content-Type", "application/json")
                .header("Idempotency-Key", digest).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            assertThat(client.send(preview, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
            // The completed body was not retained. A fresh CLI credential must recover the same persisted result.
            var first = cli(repo, origin, mint(client, origin, access));
            assertThat(first.path("changedFiles").asInt()).as("Actual CLI tracked diff collection").isEqualTo(1);
            assertThat(first.path("scope").asText()).isEqualTo("ONLINE_PREFLIGHT");
            assertThat(first.path("productionPolicyVerified").asBoolean()).isTrue();
            assertThat(first.path("scanComplete").asBoolean()).isTrue();
            assertThat(first.path("llmExecuted").asBoolean()).isFalse();
            assertThat(first.path("findings").size()).isEqualTo(1);
            assertThat(first.path("findings").get(0).path("ruleId").asText()).isEqualTo("RG-JAVA-003");
            assertThat(first.path("requestSha256").asText()).isEqualTo(digest);
            var replay = client.send(preview, HttpResponse.BodyHandlers.ofString());
            assertThat(replay.statusCode()).isEqualTo(200);
            var server = JSON.readTree(replay.body()).path("data");
            assertThat(first.path("findings")).as("Actual preview and CLI finding fingerprints").isEqualTo(server.path("findings"));
            assertThat(first.path("policyFingerprint")).as("Actual preview and CLI policy identity").isEqualTo(server.path("policyFingerprint"));
            assertThat(cli(repo, origin, mint(client, origin, access)).path("findings")).isEqualTo(first.path("findings"));
            verify(ENGINE, times(1)).review(any(PullRequestDiff.class), any(ReviewDeadline.class), anyMap());
            assertThat(jdbc.queryForObject("select count(*) from preflight_preview where user_id=?", Long.class, user.getId())).isEqualTo(1);
            var ordinary = HttpRequest.newBuilder(origin.resolve("/api/v1/preflight/preview"))
                .header("Authorization", "Bearer " + access).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            assertThat(client.send(ordinary, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
            var escaped = HttpRequest.newBuilder(origin.resolve("/api/v1/auth/me"))
                .header("Authorization", "Bearer " + shortToken).GET().build();
            assertThat(client.send(escaped, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
            jdbc.update("update user_account set session_version=session_version+1 where id=?", user.getId());
            var revoked = HttpRequest.newBuilder(origin.resolve("/api/v1/preflight/preview"))
                .header("Authorization", "Bearer " + shortToken).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            assertThat(client.send(revoked, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
            assertThat(jdbc.queryForObject("select count(*) from review_task", Long.class)).isEqualTo(reviewsBefore);
            verifyNoInteractions(MODEL);
        }
    }

    private String mint(HttpClient client, URI origin, String access) throws Exception {
        var request = HttpRequest.newBuilder(origin.resolve("/api/v1/preflight/credentials"))
            .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + access)
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"repository\":\"fixture/repo\"}")).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        return JSON.readTree(response.body()).path("data").path("credential").asText();
    }

    private JsonNode cli(Path repo, URI origin, String credential) throws Exception {
        Path cli = Path.of("..", "scripts", "repoguard-review.py").toAbsolutePath().normalize();
        String python = System.getenv().getOrDefault("REPOGUARD_PREFLIGHT_PYTHON", "python3");
        var process = new ProcessBuilder(python, "-B", "-X", "utf8", cli.toString(), "--repo", repo.toString(),
            "--base", "HEAD", "--api-url", origin.toString(), "--repository", "fixture/repo", "--preflight-token-stdin", "--format", "json");
        process.directory(repo.toFile());
        process.environment().putAll(Map.of("SSL_CERT_FILE", temporary.resolve("fixture.pem").toString(),
            "NO_PROXY", "localhost,127.0.0.1", "GIT_CONFIG_NOSYSTEM", "1",
            "GIT_CONFIG_GLOBAL", System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null"));
        process.environment().remove("GIT_DIR"); process.environment().remove("GIT_WORK_TREE");
        Path output = temporary.resolve(UUID.randomUUID() + ".json"); Path error = temporary.resolve(UUID.randomUUID() + ".txt");
        process.redirectOutput(output.toFile()); process.redirectError(error.toFile());
        var child = process.start();
        try {
            try (var input = child.getOutputStream()) { input.write((credential + "\n").getBytes(StandardCharsets.UTF_8)); }
            assertThat(child.waitFor(50, TimeUnit.SECONDS)).as("Actual CLI total deadline").isTrue();
            assertThat(child.exitValue()).as("Actual scoped CLI exit; credential and stderr withheld").isZero();
            return JSON.readTree(Files.readAllBytes(output));
        } finally { if (child.isAlive()) child.destroyForcibly(); }
    }

    private String run(Path directory, Map<String, String> environment, String... command) throws Exception {
        var process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(false);
        process.environment().putAll(environment);
        process.environment().putAll(Map.of("GIT_CONFIG_NOSYSTEM", "1",
            "GIT_CONFIG_GLOBAL", System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null"));
        process.environment().remove("GIT_DIR"); process.environment().remove("GIT_WORK_TREE");
        Path log = temporary.resolve(UUID.randomUUID() + ".txt"); process.redirectOutput(log.toFile());
        process.redirectError(temporary.resolve(UUID.randomUUID() + ".txt").toFile());
        var child = process.start();
        try {
            assertThat(child.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).as("Isolated fixture command exit").isZero();
            return command[0].equals("git") ? Files.readString(log, StandardCharsets.UTF_8) : "";
        } finally { if (child.isAlive()) child.destroyForcibly(); }
    }

    @Configuration @EnableWebMvc @EnableTransactionManagement
    static class Fixture implements WebMvcConfigurer {
        @Bean DataSource data() { return data; }
        @Bean PlatformTransactionManager transactions(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean SqlSessionFactory factory(DataSource source) throws Exception {
            var configuration = new MybatisConfiguration(); configuration.addMapper(PreflightPreviewMapper.class);
            var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration);
            return factory.getObject();
        }
        @Bean PreflightPreviewMapper previews(SqlSessionFactory factory) { return new SqlSessionTemplate(factory).getMapper(PreflightPreviewMapper.class); }
        @Bean SecretCryptoService crypto() { return new SecretCryptoService(UUID.randomUUID().toString()); }
        @Bean PreflightPreviewStore store(PreflightPreviewMapper mapper, SecretCryptoService crypto) { return new PreflightPreviewStore(mapper, crypto); }
        @Bean UserAccountMapper users() {
            var mapper = mock(UserAccountMapper.class);
            when(mapper.selectById(anyLong())).thenAnswer(invocation -> jdbc.queryForObject(
                "select id,username,role,status,session_version from user_account where id=?",
                (row, index) -> {
                    var account = new UserAccount(); account.setId(row.getLong("id")); account.setUsername(row.getString("username"));
                    account.setRole(row.getString("role")); account.setStatus(row.getString("status")); account.setSessionVersion(row.getInt("session_version"));
                    return account;
                }, invocation.getArgument(0, Long.class)));
            return mapper;
        }
        @Bean PreflightCredentialService credentials(UserAccountMapper users) {
            return new PreflightCredentialService(AUTH, users, new TenantProperties(), mock(TenantResolutionService.class));
        }
        @Bean PreflightPreviewService preview(PreflightPreviewStore store, PreflightCredentialService credentials) {
            var rule = new ReviewRuleSettings("RG-JAVA-003", "ENABLED", "*", "MEDIUM", 80, EnforcementMode.OBSERVE, "", "");
            var provider = mock(ReviewRuleProvider.class); when(provider.getRulesById()).thenReturn(Map.of(rule.id(), rule));
            var registry = new ReviewRuleRegistry(List.of(new FixedSleepRule(new RuleMatchFactory())), List.of());
            var rules = new RuleBasedPullRequestReviewer(provider, registry, new FindingPolicyResolver(), new ReviewFindingFactory(),
                new ReviewFindingSemanticDeduplicator(), new ServerRiskAggregator());
            rules = spy(rules);
            ENGINE = rules;
            var evaluator = new RepositoryPolicyEvaluationService(new ServerRiskAggregator());
            var policies = mock(RepositoryPolicyRuntime.class);
            when(policies.preview("fixture", "repo", null)).thenReturn(evaluator.evaluate(ReviewPolicySettings.empty(),
                Map.of(rule.id(), rule), null, null, List.of(), List.of()));
            var integrations = mock(GithubIntegrationProvider.class);
            when(integrations.getSettings()).thenReturn(new GithubIntegrationSettings("GITHUB", "CONFIGURED", null, null, null, "fixture", "repo", 1L));
            return new PreflightPreviewService(policies, evaluator, rules, MODEL, store, credentials,
                new TenantProperties(), mock(TenantRepositoryResolver.class), integrations, JSON, false, provider);
        }
        @Bean PreflightController controller(PreflightPreviewService service, PreflightCredentialService credentials) {
            return new PreflightController(service, credentials, mock(AuthAttemptLimiter.class), mock(AuditClientIpResolver.class), true);
        }
        @Bean GlobalExceptionHandler errors() { return new GlobalExceptionHandler(); }
        @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(new RoleAuthorizationInterceptor(JSON)); }
    }
}
