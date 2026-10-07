package com.repoguard.agent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.backup.BackupStatusQuery;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Runs actual product scripts and systemd only on an explicitly enabled, fresh hosted CI runner. */
@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "REPOGUARD_RUN_LINUX_BACKUP_INTEGRATION", matches = "true")
class ScheduledMysqlBackupLinuxIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path ROOT = Path.of("/opt/repoguard");
    private static final Path BACKUPS = ROOT.resolve("backups/scheduled-mysql");
    private static final String IMAGE = "mysql:8.0.46@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b";
    private static final String MYSQL = "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" exec mysql --protocol=TCP --host=127.0.0.1 --batch --skip-column-names --unbuffered --user=root repoguard_ci_backup --execute=\"$1\"";
    private static final String OWNER = "backup-" + UUID.randomUUID().toString().replace("-", "");
    private static final String UNIT = "repoguard-ci-" + OWNER;
    private static final List<String> CONTAINERS = new ArrayList<>();
    private static final byte[] KEY = new byte[32];
    @TempDir static Path temporary;
    private static Path repository;
    private static String source;
    private static boolean rootClaimed;
    private static boolean unitsInstalled;

    @BeforeAll static void prepare() throws Exception {
        assertThat(System.getenv("GITHUB_ACTIONS")).isEqualTo("true");
        assertThat(System.getenv("RUNNER_ENVIRONMENT")).isEqualTo("github-hosted");
        assertThat(System.getenv("GITHUB_RUN_ID")).matches("[1-9][0-9]*");
        assertThat(Files.readString(Path.of("/proc/1/comm")).strip()).isEqualTo("systemd");
        repository = Path.of(System.getenv("GITHUB_WORKSPACE")).toRealPath();
        assertThat(repository.resolve("scripts/scheduled-mysql-backup.py")).isRegularFile();
        assertThat(ROOT).doesNotExist();
        checked(List.of("sudo", "-n", "mkdir", "--", ROOT.toString()));
        rootClaimed = true;
        Path marker = temporary.resolve("owner"); Files.writeString(marker, OWNER);
        checked(List.of("sudo", "-n", "install", "-m", "0600", "--", marker.toString(), ROOT.resolve(".ci-owner").toString()));
        checked(List.of("sudo", "-n", "install", "-d", "-m", "0700", ROOT.resolve("backups").toString()));
        checked(List.of("sudo", "-n", "install", "-d", "-m", "0755", ROOT.resolve("scripts").toString(), ROOT.resolve("backup-status").toString()));
        for (String name : List.of("scheduled-mysql-backup.py", "backup-prod-mysql.sh")) {
            checked(List.of("sudo", "-n", "install", "-m", "0755", "--", repository.resolve("scripts/" + name).toString(), ROOT.resolve("scripts/" + name).toString()));
        }
        new SecureRandom().nextBytes(KEY);
        Path credential = temporary.resolve("credential");
        Files.writeString(credential, HexFormat.of().formatHex(KEY));
        Files.setPosixFilePermissions(credential, PosixFilePermissions.fromString("rw-------"));
        source = container("source");
        sql(source, "CREATE TABLE review_fixture(id INT PRIMARY KEY,note VARCHAR(80)) ENGINE=InnoDB; INSERT INTO review_fixture VALUES (1,'isolated snapshot'),(2,'unicode 临时');");
        String service = Files.readString(repository.resolve("deploy/systemd/repoguard-mysql-backup.service"))
            .replace("LoadCredential=backup-password:/etc/repoguard/backup-password", "LoadCredential=backup-password:" + credential)
            .replace("Environment=REPOGUARD_BACKUP_STATUS_PUBLISH=true", "Environment=REPOGUARD_BACKUP_STATUS_PUBLISH=true\nEnvironment=MYSQL_CONTAINER=" + source);
        Path unit = temporary.resolve(UNIT + ".service"); Files.writeString(unit, service);
        String timer = Files.readString(repository.resolve("deploy/systemd/repoguard-mysql-backup.timer"))
            .replace("Unit=repoguard-mysql-backup.service", "Unit=" + UNIT + ".service");
        Path timerFile = temporary.resolve(UNIT + ".timer"); Files.writeString(timerFile, timer);
        checked(List.of("sudo", "-n", "install", "-m", "0644", "--", unit.toString(), "/run/systemd/system/" + UNIT + ".service"));
        checked(List.of("sudo", "-n", "install", "-m", "0644", "--", timerFile.toString(), "/run/systemd/system/" + UNIT + ".timer"));
        unitsInstalled = true;
        checked(List.of("sudo", "-n", "systemd-analyze", "verify", "/run/systemd/system/" + UNIT + ".service", "/run/systemd/system/" + UNIT + ".timer"));
        checked(List.of("sudo", "-n", "systemctl", "daemon-reload"));
    }

    @Test void verifiesRealTimerEncryptedRestoreRetentionAndInterruptedStatus() throws Exception {
        assertThat(property("MemoryMax")).isEqualTo("201326592");
        assertThat(property("TasksMax")).isEqualTo("32");
        assertThat(property("ProtectSystem")).isEqualTo("strict");
        assertThat(property("NoNewPrivileges")).isEqualTo("yes");
        assertThat(property("StartLimitBurst")).isEqualTo("3");
        runBackup();
        JsonNode first = status(); String firstName = first.path("archive").path("name").asText();
        assertThat(firstName).matches("repoguard-[0-9]{8}T[0-9]{6}Z\\.sql\\.gz\\.enc");
        String restore = container("restore");
        byte[] compressed = command(List.of("sudo", "-n", "openssl", "enc", "-d", "-aes-256-cbc", "-pbkdf2", "-iter", "200000", "-md", "sha256", "-pass", "stdin", "-in", BACKUPS.resolve(firstName).toString()),
            (HexFormat.of().formatHex(KEY) + "\n").getBytes(StandardCharsets.US_ASCII), Map.of(), Duration.ofSeconds(30)).successful();
        byte[] dump;
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            dump = gzip.readNBytes(1024 * 1024 + 1); assertThat(dump.length).isLessThanOrEqualTo(1024 * 1024);
        }
        var importArgs = List.of("docker", "exec", "-i", restore, "sh", "-c", "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" exec mysql --user=root --binary-mode=1");
        command(importArgs, dump, Map.of(), Duration.ofSeconds(30)).successful();
        assertThat(sql(restore, "SELECT GROUP_CONCAT(CONCAT(id,':',note) ORDER BY id) FROM review_fixture;")).isEqualTo("1:isolated snapshot,2:unicode 临时");
        sql(source, "INSERT INTO review_fixture VALUES (3,'source after snapshot');");
        assertThat(sql(restore, "SELECT COUNT(*) FROM review_fixture;")).isEqualTo("2");
        assertThat(sql(source, "SELECT COUNT(*) FROM review_fixture;")).isEqualTo("3");
        JsonNode publicStatus = json(List.of("sudo", "-n", "cat", "--", ROOT.resolve("backup-status/status.json").toString()));
        assertThat(publicStatus.path("state").asText()).isEqualTo("success");
        assertThat(publicStatus.path("archive").has("sha256")).isFalse();
        assertThat(publicStatus.path("archive").has("name")).isFalse();
        var publicRecord = new BackupStatusQuery(JSON, ROOT.resolve("backup-status/status.json").toString(), 30).read();
        assertThat(publicRecord.status()).isEqualTo("SUCCESS_RECORDED");
        assertThat(publicRecord.restoreVerified()).isFalse(); assertThat(publicRecord.archiveIntegrityChecked()).isFalse();
        String readonlyProbe = "test -r /run/repoguard-backup-status/status.json; if (printf invalid > /run/repoguard-backup-status/status.json) 2>/dev/null; then exit 7; fi; test ! -e /opt/repoguard/backups; cat /run/repoguard-backup-status/status.json";
        JsonNode mounted = json(List.of("docker", "run", "--rm", "--network", "none", "--read-only", "--cap-drop", "ALL",
            "--security-opt", "no-new-privileges:true", "--memory", "64m", "--cpus", "0.25", "--label", "com.repoguard.ci.owner=" + OWNER,
            "--volume", ROOT.resolve("backup-status") + ":/run/repoguard-backup-status:ro", "--entrypoint", "sh", IMAGE, "-ec", readonlyProbe));
        assertThat(mounted).isEqualTo(publicStatus);
        assertThat(checked(List.of("sudo", "-n", "stat", "-c", "%a", BACKUPS.toString()))).isEqualTo("700");
        assertThat(checked(List.of("sudo", "-n", "stat", "-c", "%a", BACKUPS.resolve(firstName).toString()))).isEqualTo("600");
        String timerOverride = "[Timer]\nOnCalendar=\nOnActiveSec=2s\nRandomizedDelaySec=0\nAccuracySec=1ms\n";
        Path override = temporary.resolve("timer.conf"); Files.writeString(override, timerOverride);
        checked(List.of("sudo", "-n", "install", "-d", "-m", "0755", "/run/systemd/system/" + UNIT + ".timer.d"));
        checked(List.of("sudo", "-n", "install", "-m", "0644", "--", override.toString(), "/run/systemd/system/" + UNIT + ".timer.d/fixture.conf"));
        checked(List.of("sudo", "-n", "systemctl", "daemon-reload"));
        systemctl("reset-failed", UNIT + ".service");
        nextSecond(first.path("finishedAtUnix").asLong());
        systemctl("start", UNIT + ".timer");
        await(() -> { try { JsonNode s = status(); return s.path("state").asText().equals("success") && !s.path("archive").path("name").asText().equals(firstName); } catch (Exception e) { return false; } }, 45);
        systemctl("stop", UNIT + ".timer");
        assertThat(checked(List.of("systemctl", "show", "--value", "--property=LastTriggerUSecMonotonic", UNIT + ".timer"))).isNotBlank().isNotIn("0", "n/a");
        for (int count = 2; count < 8; count++) runBackup();
        assertThat(catalog().size()).isEqualTo(7);
        assertThat(catalog().findValuesAsText("name")).doesNotContain(firstName);
        assertThat(status().path("retained").asInt()).isEqualTo(7);
        verifyPersistentCatchup();
        JsonNode retained = catalog(); assertThat(retained.size()).isEqualTo(7);
        Path budgetFile = BACKUPS.resolve(".ci-budget-" + OWNER);
        checked(List.of("sudo", "-n", "test", "!", "-e", budgetFile.toString()));
        checked(List.of("sudo", "-n", "truncate", "--size=2147483648", "--", budgetFile.toString()));
        try {
            systemctl("reset-failed", UNIT + ".service");
            assertThat(command(List.of("sudo", "-n", "systemctl", "start", UNIT + ".service"), null, Map.of(), Duration.ofSeconds(30)).exit()).isNotZero();
            assertThat(status().path("reason").asText()).isEqualTo("backup_storage_budget_exhausted");
            assertThat(catalog()).isEqualTo(retained);
        } finally {
            systemctl("stop", UNIT + ".service");
            checked(List.of("sudo", "-n", "rm", "--", budgetFile.toString()));
        }
        String archiveName = retained.get(0).path("name").asText();
        Path archive = BACKUPS.resolve(archiveName);
        byte[] original = command(List.of("sudo", "-n", "cat", "--", archive.toString()), null, Map.of(), Duration.ofSeconds(10)).successful();
        byte[] corrupt = original.clone(); corrupt[corrupt.length - 1] ^= 1;
        overwrite(archive, corrupt);
        systemctl("reset-failed", UNIT + ".service");
        for (int count = 0; count < 3; count++) {
            assertThat(command(List.of("sudo", "-n", "systemctl", "start", UNIT + ".service"), null, Map.of(), Duration.ofSeconds(30)).exit()).isNotZero();
            assertThat(status().path("reason").asText()).isEqualTo("archive_checksum_mismatch");
            systemctl("stop", UNIT + ".service");
        }
        assertThat(command(List.of("sudo", "-n", "systemctl", "start", UNIT + ".service"), null, Map.of(), Duration.ofSeconds(30)).exit()).isNotZero();
        assertThat(property("Result")).isEqualTo("start-limit-hit");
        overwrite(archive, original);
        assertThat(catalog().size()).isEqualTo(7);
        interruptWhileMysqlIsLocked();
        assertThat(status().path("state").asText()).isEqualTo("failed");
        assertThat(status().path("reason").asText()).isEqualTo("service_interrupted");
        assertThat(catalog().size()).isEqualTo(7);
        runBackup(); assertThat(status().path("state").asText()).isEqualTo("success");
        assertThat(catalog().size()).isEqualTo(7);
    }

    private static void verifyPersistentCatchup() throws Exception {
        String before = status().path("archive").path("name").asText();
        Instant scheduled = Instant.now().plusSeconds(8);
        String calendar = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC).format(scheduled);
        String configuration = "[Timer]\nOnCalendar=\nOnActiveSec=\nOnCalendar=" + calendar + "\nRandomizedDelaySec=0\nAccuracySec=1ms\n";
        Path override = temporary.resolve("catchup.conf"); Files.writeString(override, configuration);
        checked(List.of("sudo", "-n", "install", "-m", "0644", "--", override.toString(), "/run/systemd/system/" + UNIT + ".timer.d/fixture.conf"));
        checked(List.of("sudo", "-n", "systemctl", "daemon-reload"));
        systemctl("reset-failed", UNIT + ".service");
        systemctl("start", UNIT + ".timer"); systemctl("stop", UNIT + ".timer");
        assertThat(Instant.now().getEpochSecond()).isLessThan(scheduled.getEpochSecond());
        await(() -> Instant.now().getEpochSecond() > scheduled.getEpochSecond(), 12);
        systemctl("start", UNIT + ".timer");
        try {
            await(() -> { try { JsonNode s = status(); return s.path("state").asText().equals("success") && !s.path("archive").path("name").asText().equals(before); } catch (Exception e) { return false; } }, 45);
        } finally { systemctl("stop", UNIT + ".timer"); }
    }

    private static void interruptWhileMysqlIsLocked() throws Exception {
        var lock = new ProcessBuilder(mysqlArgs(source, "LOCK TABLES review_fixture WRITE; SELECT CONCAT('HELD:',CONNECTION_ID()); DO SLEEP(120);"))
            .redirectErrorStream(true).start();
        String connection = null;
        var reader = new BufferedReader(new InputStreamReader(lock.getInputStream(), StandardCharsets.UTF_8));
        try {
            var line = CompletableFuture.supplyAsync(() -> { try { return reader.readLine(); } catch (Exception e) { throw new IllegalStateException(e); } });
            String held = line.get(10, TimeUnit.SECONDS); assertThat(held).matches("HELD:[1-9][0-9]*"); connection = held.substring(5);
            systemctl("reset-failed", UNIT + ".service");
            long before = status().path("startedAtUnix").asLong(); nextSecond(before);
            systemctl("start", "--no-block", UNIT + ".service");
            await(() -> { try { JsonNode s = status(); return s.path("state").asText().equals("running") && s.path("startedAtUnix").asLong() > before; } catch (Exception e) { return false; } }, 15);
            assertThat(Long.parseLong(property("MainPID"))).isPositive();
            assertThat(Long.parseLong(property("MemoryCurrent"))).isPositive().isLessThanOrEqualTo(192L * 1024 * 1024);
            systemctl("kill", "--kill-whom=main", "--signal=SIGKILL", UNIT + ".service");
            await(() -> { try { return status().path("reason").asText().equals("service_interrupted"); } catch (Exception e) { return false; } }, 15);
        } finally {
            try { if (connection != null) sql(source, "KILL " + connection + ";"); }
            finally {
                lock.destroyForcibly(); lock.waitFor(10, TimeUnit.SECONDS); reader.close();
                systemctl("stop", UNIT + ".service");
            }
        }
    }

    private static void runBackup() throws Exception {
        if (command(List.of("sudo", "-n", "test", "-f", BACKUPS.resolve("status.json").toString()), null, Map.of(), Duration.ofSeconds(5)).exit() == 0) nextSecond(status().path("finishedAtUnix").asLong());
        systemctl("reset-failed", UNIT + ".service");
        Result started = command(List.of("sudo", "-n", "systemctl", "start", UNIT + ".service"), null, Map.of(), Duration.ofMinutes(3));
        if (started.exit() != 0) reportFixtureFailure();
        started.successful();
        assertThat(status().path("state").asText()).isEqualTo("success");
    }
    private static void reportFixtureFailure() {
        try {
            System.out.println("Isolated backup unit: Result=" + property("Result") + ", ExecMainStatus=" + property("ExecMainStatus"));
            Result journal = command(List.of("sudo", "-n", "journalctl", "--unit=" + UNIT + ".service", "--no-pager", "--output=cat", "--lines=30"), null, Map.of(), Duration.ofSeconds(10));
            String diagnostic = new String(journal.output(), StandardCharsets.UTF_8)
                .replace(HexFormat.of().formatHex(KEY), "[masked]").replaceAll("[a-fA-F0-9]{32,}", "[masked]");
            System.out.println("Isolated fixture journal: " + diagnostic.substring(0, Math.min(4096, diagnostic.length())));
        } catch (Exception ignored) { System.out.println("Isolated fixture diagnostics unavailable"); }
    }
    private static void nextSecond(long timestamp) throws Exception {
        await(() -> System.currentTimeMillis() / 1000 > timestamp, 3);
    }
    private static String container(String role) throws Exception {
        String name = "repoguard-ci-" + OWNER + "-" + role;
        String password = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        List<String> args = List.of("docker", "run", "--detach", "--name", name, "--label", "com.repoguard.ci.owner=" + OWNER,
            "--network", "none", "--memory", "512m", "--memory-swap", "512m", "--cpus", "0.50", "--restart", "no",
            "--env", "MYSQL_ROOT_PASSWORD", "--env", "MYSQL_DATABASE=repoguard_ci_backup",
            "--health-cmd", "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql --protocol=TCP --host=127.0.0.1 --user=root --database=repoguard_ci_backup --execute='SELECT 1' >/dev/null", "--health-interval", "1s", "--health-timeout", "3s", "--health-retries", "90",
            IMAGE, "--innodb-buffer-pool-size=64M", "--max-connections=16", "--performance-schema=OFF");
        checked(args, Map.of("MYSQL_ROOT_PASSWORD", password)); CONTAINERS.add(name);
        await(() -> { try { return checked(List.of("docker", "inspect", "--format", "{{.State.Health.Status}}", name)).equals("healthy"); } catch (Exception e) { return false; } }, 120);
        assertThat(sql(name, "SELECT 1;")).isEqualTo("1");
        assertThat(checked(List.of("docker", "inspect", "--format", "{{.HostConfig.NetworkMode}}", name))).isEqualTo("none");
        return name;
    }
    private static List<String> mysqlArgs(String container, String sql) {
        return List.of("docker", "exec", container, "sh", "-c", MYSQL, "sh", sql);
    }
    private static String sql(String container, String sql) throws Exception { return checked(mysqlArgs(container, sql)); }
    private static JsonNode status() throws Exception { return json(List.of("sudo", "-n", "cat", "--", BACKUPS.resolve("status.json").toString())); }
    private static JsonNode catalog() throws Exception { return json(List.of("sudo", "-n", "python3", ROOT.resolve("scripts/scheduled-mysql-backup.py").toString(), "catalog")); }
    private static JsonNode json(List<String> args) throws Exception { return JSON.readTree(checked(args)); }
    private static String property(String name) throws Exception { return checked(List.of("systemctl", "show", "--value", "--property=" + name, UNIT + ".service")); }
    private static void overwrite(Path target, byte[] bytes) throws Exception {
        Path sourceFile = temporary.resolve("replacement"); Files.write(sourceFile, bytes);
        checked(List.of("sudo", "-n", "install", "-m", "0600", "--", sourceFile.toString(), target.toString()));
    }
    private static void systemctl(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("sudo", "-n", "systemctl")); command.addAll(List.of(args)); checked(command);
    }
    private static String checked(List<String> args) throws Exception { return checked(args, Map.of()); }
    private static String checked(List<String> args, Map<String, String> env) throws Exception {
        return new String(command(args, null, env, Duration.ofMinutes(3)).successful(), StandardCharsets.UTF_8).strip();
    }
    private record Result(int exit, byte[] output) {
        byte[] successful() { assertThat(exit).as("Isolated command completed successfully; command output intentionally omitted").isZero(); return output; }
    }
    private static Result command(List<String> args, byte[] input, Map<String, String> env, Duration timeout) throws Exception {
        var builder = new ProcessBuilder(args).redirectErrorStream(true); builder.environment().putAll(env);
        var process = builder.start();
        var output = CompletableFuture.supplyAsync(() -> {
            try { byte[] bytes = process.getInputStream().readNBytes(1024 * 1024 + 1); if (bytes.length > 1024 * 1024) throw new IllegalStateException("Isolated command output budget exceeded"); return bytes; }
            catch (Exception e) { throw new IllegalStateException("Isolated command output unavailable", e); }
        });
        try {
            if (input != null) process.getOutputStream().write(input); process.getOutputStream().close();
            assertThat(process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)).as("Isolated command deadline").isTrue();
            return new Result(process.exitValue(), output.get(10, TimeUnit.SECONDS));
        } finally { process.descendants().forEach(ProcessHandle::destroyForcibly); if (process.isAlive()) process.destroyForcibly(); }
    }
    private static void await(BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean()) { if (System.nanoTime() >= deadline) throw new AssertionError("Isolated fixture deadline"); Thread.sleep(50); }
    }

    @AfterAll static void close() throws Exception {
        try {
            if (unitsInstalled) {
                // A failed ExecStopPost may make stop return nonzero even after all processes exit.
                command(List.of("sudo", "-n", "systemctl", "stop", UNIT + ".timer", UNIT + ".service"), null, Map.of(), Duration.ofSeconds(30));
                assertThat(property("MainPID")).isEqualTo("0");
                assertThat(property("ActiveState")).isIn("inactive", "failed");
                systemctl("reset-failed", UNIT + ".service");
                checked(List.of("sudo", "-n", "rm", "-f", "--", "/run/systemd/system/" + UNIT + ".service", "/run/systemd/system/" + UNIT + ".timer", "/run/systemd/system/" + UNIT + ".timer.d/fixture.conf"));
                checked(List.of("sudo", "-n", "systemctl", "daemon-reload"));
            }
        } finally {
            try {
                for (String container : CONTAINERS) {
                    String identity = checked(List.of("docker", "inspect", "--format", "{{.Id}} {{index .Config.Labels \"com.repoguard.ci.owner\"}}", container));
                    String[] parts = identity.split(" ", 2);
                    assertThat(parts).hasSize(2); assertThat(parts[0]).matches("[a-f0-9]{64}"); assertThat(parts[1]).isEqualTo(OWNER);
                    checked(List.of("docker", "rm", "--force", "--volumes", parts[0]));
                }
                if (rootClaimed) assertThat(checked(List.of("sudo", "-n", "cat", "--", ROOT.resolve(".ci-owner").toString()))).isEqualTo(OWNER);
            } finally { java.util.Arrays.fill(KEY, (byte) 0); }
        }
    }
}
