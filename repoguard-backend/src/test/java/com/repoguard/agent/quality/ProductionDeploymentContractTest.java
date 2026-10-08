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

/**
 * Protects the production deployment invariants that cannot be covered by a
 * Spring unit test: tracked bind assets, migration-owner ordering, broker
 * timeout layering, pre-mutation validation, and asset-aware rollback.
 */
class ProductionDeploymentContractTest {

    @Test
    void frontendSseBuildRequiresExplicitManualOptInAndImmutableImageLabel() throws IOException {
        String workflow = read(repositoryRoot().resolve(".github/workflows/release-images.yml"));
        assertThat(workflow).contains("frontend_progress_stream:",
            "github.event_name == 'workflow_dispatch' && inputs.frontend_progress_stream == true",
            "REVIEW_PROGRESS_STREAM=${{ env.VITE_REVIEW_PROGRESS_STREAM }}");
        Map<String, Object> root = yaml(repositoryRoot().resolve(".github/workflows/release-images.yml"));
        Map<String, Object> events = map(root.getOrDefault("on", root.get(Boolean.TRUE)));
        Map<String, Object> option = map(map(map(events.get("workflow_dispatch")).get("inputs"))
            .get("frontend_progress_stream"));
        assertThat(option).containsEntry("default", false).containsEntry("type", "boolean");
        assertThat(read(repositoryRoot().resolve("repoguard-frontend/Dockerfile")))
            .contains("ARG REVIEW_PROGRESS_STREAM=false",
                "io.repoguard.frontend.review-progress-stream=\"${REVIEW_PROGRESS_STREAM}\"");
    }

    @Test
    void evaluationRuntimeRevisionUsesThePublishedImageCommit() throws IOException {
        String dockerfile = read(repositoryRoot().resolve("repoguard-backend/Dockerfile"));
        String workflow = read(repositoryRoot().resolve(".github/workflows/release-images.yml"));
        assertThat(dockerfile).contains("ARG OCI_REVISION", "ENV REPOGUARD_RUNTIME_REVISION=${OCI_REVISION}");
        assertThat(workflow).contains("OCI_REVISION=${{ github.sha }}");
    }

    @Test
    void requiredBindSourcesUseFailClosedLongSyntax() throws IOException {
        Map<String, Object> services = services();

        Map<String, Object> rabbitConfig = bindMount(
            map(services.get("rabbitmq")),
            "./config/rabbitmq/rabbitmq.conf"
        );
        assertThat(rabbitConfig)
            .containsEntry("type", "bind")
            .containsEntry("target", "/etc/rabbitmq/conf.d/20-repoguard.conf")
            .containsEntry("read_only", true);
        assertThat(map(rabbitConfig.get("bind"))).containsEntry("create_host_path", false);

        Map<String, Object> caddyConfig = bindMount(map(services.get("caddy")), "./Caddyfile");
        assertThat(caddyConfig)
            .containsEntry("type", "bind")
            .containsEntry("target", "/etc/caddy/Caddyfile")
            .containsEntry("read_only", true);
        assertThat(map(caddyConfig.get("bind"))).containsEntry("create_host_path", false);
    }

    @Test
    void apiOwnsFlywayAndWorkerWaitsForTheMigrationOwner() throws IOException {
        Map<String, Object> services = services();
        Map<String, Object> backend = map(services.get("backend"));
        Map<String, Object> worker = map(services.get("backend-worker"));
        Map<String, Object> backendEnvironment = map(backend.get("environment"));
        Map<String, Object> workerEnvironment = map(worker.get("environment"));

        assertThat(backendEnvironment).doesNotContainKey("SPRING_FLYWAY_ENABLED");
        assertThat(workerEnvironment).containsEntry("SPRING_FLYWAY_ENABLED", "false");
        assertThat(map(map(worker.get("depends_on")).get("backend")))
            .containsEntry("condition", "service_healthy");
    }

    @Test
    void applicationImagesAlwaysImportComposeConfigtreeForRollbackCompatibility() throws IOException {
        Map<String, Object> services = services();
        Map<String, Object> backendEnvironment =
            map(map(services.get("backend")).get("environment"));
        Map<String, Object> workerEnvironment =
            map(map(services.get("backend-worker")).get("environment"));

        assertThat(backendEnvironment)
            .containsEntry("SPRING_CONFIG_IMPORT", "optional:configtree:/run/secrets/");
        assertThat(workerEnvironment)
            .containsEntry("SPRING_CONFIG_IMPORT", "optional:configtree:/run/secrets/");
    }

    @Test
    void brokerTimeoutStaysBetweenPipelineBudgetAndRecoveryThreshold() throws IOException {
        Map<String, Object> services = services();
        Map<String, Object> backendEnvironment =
            map(map(services.get("backend")).get("environment"));
        Map<String, Object> workerEnvironment =
            map(map(services.get("backend-worker")).get("environment"));

        int pipelineBudget = interpolationDefault(
            backendEnvironment.get("REPOGUARD_REVIEW_PIPELINE_BUDGET_MS"),
            "REPOGUARD_REVIEW_PIPELINE_BUDGET_MS"
        );
        int recoveryTimeout = interpolationDefault(
            backendEnvironment.get("REPOGUARD_REVIEW_EXECUTION_TIMEOUT_MS"),
            "REPOGUARD_REVIEW_EXECUTION_TIMEOUT_MS"
        );
        int consumerTimeout = rabbitConsumerTimeout();

        assertThat(workerEnvironment.get("REPOGUARD_REVIEW_PIPELINE_BUDGET_MS"))
            .isEqualTo(backendEnvironment.get("REPOGUARD_REVIEW_PIPELINE_BUDGET_MS"));
        assertThat(workerEnvironment.get("REPOGUARD_REVIEW_EXECUTION_TIMEOUT_MS"))
            .isEqualTo(backendEnvironment.get("REPOGUARD_REVIEW_EXECUTION_TIMEOUT_MS"));
        assertThat(pipelineBudget).isLessThan(consumerTimeout);
        assertThat(consumerTimeout).isLessThan(recoveryTimeout);
    }

    @Test
    void releaseUploadsAndBacksUpRabbitConfigBeforePublishingCompose() throws IOException {
        Path workflowPath = repositoryRoot().resolve(".github/workflows/release-images.yml");
        String workflow = read(workflowPath);

        assertThat(yaml(workflowPath)).containsKey("jobs");
        assertThat(workflow)
            .contains("name: Validate production Compose models")
            .contains("docker compose --env-file .env.prod.example")
            .contains("docker compose --profile worker-split")
            .contains("test -s config/rabbitmq/rabbitmq.conf")
            .contains("${DEPLOY_PATH}/config/rabbitmq")
            .contains("${asset_backup_dir}/config/rabbitmq/rabbitmq.conf")
            .contains("DEPLOY_ASSET_BACKUP_DIR=.deploy-backup/")
            .contains("ssh_args=(")
            .contains("scp_args=(")
            .contains("-o ConnectTimeout=15")
            .contains("-o ConnectionAttempts=1")
            .contains("timeout --signal=TERM --kill-after=10s 120s ssh")
            .contains("timeout --signal=TERM --kill-after=10s 120s scp")
            .contains("每个生产资产 SSH/SCP 操作最长 120 秒");

        int uploadSection = workflow.indexOf("# Upload bind sources before the compose file");
        assertThat(uploadSection).isNotNegative();
        String orderedUploads = workflow.substring(uploadSection);
        assertThat(orderedUploads.indexOf("config/rabbitmq/rabbitmq.conf"))
            .isNotNegative()
            .isLessThan(orderedUploads.indexOf("docker-compose.prod.yml"));
    }

    @Test
    void productionDeployIsManualOptInAndDisabledByDefault() throws IOException {
        String workflow = read(repositoryRoot().resolve(".github/workflows/release-images.yml"));

        int deployInputStart = workflow.indexOf("\n      deploy:\n");
        int migrationInputStart = workflow.indexOf("\n      migrate_legacy_secret_files:", deployInputStart);
        assertThat(deployInputStart).isNotNegative();
        assertThat(migrationInputStart).isGreaterThan(deployInputStart);
        assertThat(workflow.substring(deployInputStart, migrationInputStart))
            .contains(
                "description: Deploy to the configured server (manual opt-in; default off)",
                "required: false",
                "default: false",
                "type: boolean"
            );
        assertThat(workflow)
            .contains(
                "migrate_legacy_secret_files:\n"
                    + "        description: Preserve active inline values in secret files before the first configtree deployment\n"
                    + "        required: false\n"
                    + "        default: false\n"
                    + "        type: boolean",
                "initialize_missing_encryption_salt:\n"
                    + "        description: Initialize the salt introduced after the currently deployed legacy release\n"
                    + "        required: false\n"
                    + "        default: false\n"
                    + "        type: boolean"
            );

        int deployJobStart = workflow.indexOf("\n  deploy:\n");
        assertThat(deployJobStart).isNotNegative();
        String deployJob = workflow.substring(deployJobStart);
        assertThat(deployJob)
            .contains(
                "name: Deploy to production (manual opt-in)",
                "github.event_name == 'workflow_dispatch'",
                "inputs.deploy == true",
                "github.ref_name == 'main'",
                "github.ref_name == 'master'",
                "startsWith(github.ref, 'refs/tags/v')"
            )
            .doesNotContain("github.ref_name == 'PRAgent-test'");
    }

    @Test
    void releaseKeepsAcrImagesCompatibleAndPublishesExternalAttestations() throws IOException {
        String workflow = read(repositoryRoot().resolve(".github/workflows/release-images.yml"));

        assertThat(workflow)
            .contains(
                "attestations: write",
                "id-token: write",
                "sbom: false",
                "provenance: false",
                "name: Generate backend SBOM",
                "name: Generate frontend SBOM",
                "anchore/sbom-action@3ad7283483fc7af8ff2b4ea19663c2d5ca935e26",
                "actions/attest-build-provenance@4d101475d8b20a2381f78447822ac1eab6504dd8",
                "actions/attest@1e69f48acb82d1966a394da916b4c1698aa569d6",
                "subject-digest: ${{ steps.backend_image.outputs.digest }}",
                "subject-digest: ${{ steps.frontend_image.outputs.digest }}"
            )
            .doesNotContain("sbom: true", "provenance: mode=max");
    }

    @Test
    void releaseMirrorsOnlyScannedDigestsInsideProtectedProductionDeployment() throws IOException {
        String workflow = read(repositoryRoot().resolve(".github/workflows/release-images.yml"));

        assertThat(workflow)
            .contains("image-ref: ${{ env.BACKEND_IMAGE }}@${{ steps.backend_image.outputs.digest }}")
            .contains("image-ref: ${{ env.FRONTEND_IMAGE }}@${{ steps.frontend_image.outputs.digest }}")
            .contains("environment: production")
            .contains("name: Mirror checked images to Aliyun ACR over VPC")
            .contains("if: ${{ env.RELEASE_MANIFEST_KIND == 'source' }}")
            .contains("BACKEND_DIGEST: ${{ env.SOURCE_BACKEND_DIGEST }}")
            .contains("FRONTEND_DIGEST: ${{ env.SOURCE_FRONTEND_DIGEST }}")
            .contains("BACKEND_SOURCE=\"${SOURCE_BACKEND_IMAGE}\"")
            .contains("FRONTEND_SOURCE=\"${SOURCE_FRONTEND_IMAGE}\"")
            .contains("timeout 600s docker pull \"$BACKEND_SOURCE\"")
            .contains("timeout 600s docker pull \"$FRONTEND_SOURCE\"")
            .contains("docker save \"$backend_transfer\" \"$frontend_transfer\" | gzip -1 > \"$archive\"")
            .contains("split -b 8m -d -a 5 \"$archive\" \"${transfer_dir}/part-\"")
            .contains("xargs -0 -r -n1 -P8 bash -c 'upload_chunk \"$1\"' _")
            .contains("test \"$remote_sha\" = \"$archive_sha\"")
            .contains("gzip -dc | docker load")
            .contains("test \\\"\\$(docker image inspect --format '{{.Id}}' '${backend_transfer}')\\\" = \\\"\\$(docker image inspect --format '{{.Id}}' '${BACKEND_TARGET}')\\\"")
            .contains("test \\\"\\$(docker image inspect --format '{{.Id}}' '${frontend_transfer}')\\\" = \\\"\\$(docker image inspect --format '{{.Id}}' '${FRONTEND_TARGET}')\\\"")
            .doesNotContain("= '${backend_image_id}'", "= '${frontend_image_id}'")
            .contains("docker manifest inspect '${BACKEND_TARGET}'")
            .contains("docker manifest inspect '${FRONTEND_TARGET}'")
            .doesNotContain(
                "needs.build.outputs.backend_image",
                "needs.build.outputs.frontend_image",
                "timeout 600s docker pull '${BACKEND_SOURCE}'",
                "timeout 600s docker pull '${FRONTEND_SOURCE}'"
            );

        int backendScan = workflow.indexOf("- name: Scan backend image for high and critical CVEs");
        int frontendScan = workflow.indexOf("- name: Scan frontend image for high and critical CVEs");
        int mirror = workflow.indexOf("- name: Mirror checked images to Aliyun ACR over VPC");
        int restart = workflow.indexOf("- name: Pull and restart");
        assertThat(backendScan).isPositive().isLessThan(mirror);
        assertThat(frontendScan).isPositive().isLessThan(mirror);
        assertThat(mirror).isLessThan(restart);
    }

    @Test
    void releaseManifestBindsBothScansAndIsVerifiedBeforeProductionAssets() throws IOException {
        String workflow = read(repositoryRoot().resolve(".github/workflows/release-images.yml"));
        assertThat(workflow.indexOf("- name: Bind checked source images"))
            .isGreaterThan(workflow.indexOf("- name: Scan frontend image"));
        assertThat(workflow.indexOf("- name: Load verified release manifest"))
            .isLessThan(workflow.indexOf("- name: Upload deployment and smoke assets"));
        assertThat(workflow).contains("release_run_id:", "subject-path: ${{ runner.temp }}/release-source-manifest.json",
            "approved-release-manifest-${{ github.run_id }}-${{ github.run_attempt }}",
            "backend_image=\"${RELEASE_BACKEND_IMAGE}\"", "RELEASE_MANIFEST_SHA256='${RELEASE_MANIFEST_SHA256}'");
        List<Object> buildSteps = list(map(map(yaml(repositoryRoot().resolve(
            ".github/workflows/release-images.yml")).get("jobs")).get("build")).get("steps"));
        String trustedRef = "${{ github.ref == 'refs/heads/main' || github.ref == 'refs/heads/master'"
            + " || startsWith(github.ref, 'refs/tags/v') }}";
        for (String stepName : List.of("Bind checked source images to release manifest",
            "Attest checked release manifest", "Preserve verified source manifest")) {
            Map<String, Object> step = buildSteps.stream().map(this::map)
                .filter(candidate -> stepName.equals(candidate.get("name"))).findFirst().orElseThrow();
            assertThat(step).as("Only trusted refs may seal reusable production provenance")
                .containsEntry("if", trustedRef);
        }
        for (String stepName : List.of("Scan backend image for high and critical CVEs",
            "Scan frontend image for high and critical CVEs")) {
            Map<String, Object> step = buildSteps.stream().map(this::map)
                .filter(candidate -> stepName.equals(candidate.get("name"))).findFirst().orElseThrow();
            assertThat(step).as("Manual branch builds must still pass both image scans")
                .doesNotContainKey("if");
        }
        String verifier = read(repositoryRoot().resolve("scripts/release-manifest.py"));
        assertThat(verifier).contains("--source-digest", "--source-ref", "--deny-self-hosted-runners", "--signer-workflow",
            "Attestation belongs to a different run", "Promotion changed the scanned platform image");
        String script = read(repositoryRoot().resolve("scripts/deploy-prod.sh"));
        assertThat(script.lastIndexOf("\nvalidate_release_manifest\n")).isLessThan(script.lastIndexOf("\ncompose pull $deploy_services\n"));
        assertThat(script).contains("$EXPECTED_RELEASE_SHA", "$EXPECTED_BACKEND_IMAGE_ID", "verify_release_schema");
    }

    @Test
    void deployPreflightsBeforeMutationAndRollbackRestoresAssetsFirst() throws IOException {
        String script = read(repositoryRoot().resolve("scripts/deploy-prod.sh"));

        int bindPreflight = script.lastIndexOf("\nvalidate_required_bind_sources\n");
        int secretPreflight = script.lastIndexOf("\nvalidate_secret_files\n");
        int edgePreflight = script.lastIndexOf("\nvalidate_edge_observability_isolation\n");
        int timeoutPreflight = script.lastIndexOf("\nvalidate_review_timeout_layering\n");
        int rabbitRestartDecision = script.lastIndexOf("\nif rabbitmq_config_requires_restart; then\n");
        int stopWorker = script.lastIndexOf("\nstop_inactive_split_worker\n");
        int infrastructureMutation = script.lastIndexOf("\ncompose up -d --no-deps mysql\n");
        int rollbackArmed = script.lastIndexOf("\nrollback_needed=true\n");
        int preflightOnlyExit = script.indexOf("if [ \"$PREFLIGHT_ONLY\" = \"true\" ]; then");
        int imagePull = script.indexOf("\ncompose pull $deploy_services\n");
        int secretMountPreflight = script.lastIndexOf("\nvalidate_backend_secret_mounts\n");

        assertThat(bindPreflight).isNotNegative().isLessThan(stopWorker);
        assertThat(secretPreflight).isNotNegative().isLessThan(stopWorker);
        assertThat(edgePreflight).isNotNegative().isLessThan(stopWorker);
        assertThat(timeoutPreflight).isNotNegative().isLessThan(stopWorker);
        assertThat(rabbitRestartDecision).isNotNegative().isLessThan(stopWorker);
        assertThat(rollbackArmed).isNotNegative().isLessThan(stopWorker);
        assertThat(stopWorker).isLessThan(infrastructureMutation);
        assertThat(preflightOnlyExit).isNotNegative().isLessThan(imagePull);
        assertThat(imagePull).isLessThan(secretMountPreflight);
        assertThat(secretMountPreflight).isLessThan(rollbackArmed);
        assertThat(script)
            .contains("compose up -d --no-deps --force-recreate rabbitmq")
            .contains("compose up -d --no-deps --force-recreate frontend")
            .contains("wait_service_health frontend 30")
            .contains("compose up -d --no-deps --force-recreate caddy")
            .contains("wait_service_health caddy 30")
            .contains("print_service_diagnostics \"$service\"")
            .contains("compose logs --tail=120 \"$service\"")
            .contains("--format '{{json .State}}'")
            .contains("restore_deployment_assets false")
            .contains(
                "Keeping the validated candidate Compose model for backward-compatible image rollback."
            )
            .contains("$DEPLOY_STATE_DIR/rabbitmq.conf.sha256")
            .contains("record_rabbitmq_config_digest")
            .contains("REPOGUARD_SECURITY_ENCRYPTION_KEY_FILE")
            .contains("REPOGUARD_GITHUB_WEBHOOK_SECRET_FILE")
            .contains("444) ;;")
            .contains("500|700")
            .contains("tail -c 1")
            .contains("contains a trailing newline")
            .contains("compose run --rm --no-deps --entrypoint sh")
            .contains("Backend image user cannot read every required Compose secret bind mount.")
            .contains("Production edge configuration must not route to observability services")
            .contains("Production deployment preflight passed; no image was pulled and no service was changed.");

        int rollbackStart = script.indexOf("rollback_deployment() {");
        int rollbackEnd = script.indexOf("\nrollback_needed=false", rollbackStart);
        String rollback = script.substring(rollbackStart, rollbackEnd);
        assertThat(rollback.indexOf("restore_deployment_assets"))
            .isNotNegative()
            .isLessThan(rollback.indexOf("compose up -d --no-deps --force-recreate rabbitmq"));
    }

    @Test
    void legacySecretMigrationIsExplicitExactAndFinalizedOnlyAfterHealth() throws IOException {
        Path root = repositoryRoot();
        String workflow = read(root.resolve(".github/workflows/release-images.yml"));
        String deploy = read(root.resolve("scripts/deploy-prod.sh"));
        String migration = read(root.resolve("scripts/migrate-prod-secret-files.sh"));

        assertThat(workflow)
            .contains("migrate_legacy_secret_files:")
            .contains("initialize_missing_encryption_salt:")
            .contains("default: false")
            .contains("name: Exercise legacy production secret migration")
            .contains("scripts/migrate-prod-secret-files.sh")
            .contains("MIGRATE_LEGACY_SECRET_FILES: ${{ inputs.migrate_legacy_secret_files }}")
            .contains("MIGRATE_LEGACY_SECRET_FILES='${MIGRATE_LEGACY_SECRET_FILES}'")
            .contains(
                "INITIALIZE_MISSING_ENCRYPTION_SALT: "
                    + "${{ inputs.initialize_missing_encryption_salt }}"
            )
            .contains(
                "INITIALIZE_MISSING_ENCRYPTION_SALT="
                    + "'${INITIALIZE_MISSING_ENCRYPTION_SALT}'"
            );

        int prepare = deploy.lastIndexOf("sh scripts/migrate-prod-secret-files.sh prepare");
        int preflight = deploy.lastIndexOf("\nvalidate_required_bind_sources\n");
        int pull = deploy.lastIndexOf("\ncompose pull $deploy_services\n");
        int healthVerification = deploy.lastIndexOf("\nverify_deployment 15 30\n");
        int rollbackDisarmed = deploy.lastIndexOf("\nrollback_needed=false\n");
        int finalize = deploy.lastIndexOf("sh scripts/migrate-prod-secret-files.sh finalize");
        assertThat(prepare).isNotNegative().isLessThan(preflight);
        assertThat(preflight).isLessThan(pull);
        assertThat(healthVerification).isLessThan(rollbackDisarmed);
        assertThat(rollbackDisarmed).isLessThan(finalize);

        assertThat(migration)
            .contains(
                "MYSQL_ROOT_PASSWORD|MYSQL_ROOT_PASSWORD_FILE|./secrets/mysql.root-password",
                "MYSQL_PASSWORD|MYSQL_PASSWORD_FILE|./secrets/spring.datasource.password",
                "REPOGUARD_SECURITY_ENCRYPTION_KEY|REPOGUARD_SECURITY_ENCRYPTION_KEY_FILE"
                    + "|./secrets/repoguard.security.encryption-key",
                "REPOGUARD_SECURITY_ENCRYPTION_SALT|REPOGUARD_SECURITY_ENCRYPTION_SALT_FILE"
                    + "|./secrets/repoguard.security.encryption-salt",
                "REPOGUARD_AUTH_TOKEN_SECRET|REPOGUARD_AUTH_TOKEN_SECRET_FILE"
                    + "|./secrets/repoguard.auth.token-secret",
                "REPOGUARD_ADMIN_API_KEY|REPOGUARD_ADMIN_API_KEY_FILE"
                    + "|./secrets/app.security.admin-api-key.key",
                "REPOGUARD_GITHUB_WEBHOOK_SECRET|REPOGUARD_GITHUB_WEBHOOK_SECRET_FILE"
                    + "|./secrets/app.github.webhook.secret"
            )
            .contains("printf '%s' \"$legacy_value\" > \"$candidate\"")
            .contains("cmp -s \"$candidate\" \"$secret_path\"")
            .contains("validation_secret_path=\"$2\"")
            .contains("INITIALIZE_MISSING_ENCRYPTION_SALT=\"${"
                + "INITIALIZE_MISSING_ENCRYPTION_SALT:-false}\"")
            .contains("openssl rand -hex 32 > \"$candidate\"")
            .contains("unset MYSQL_ROOT_PASSWORD MYSQL_ROOT_PASSWORD_FILE")
            .contains(
                "unset REPOGUARD_GITHUB_WEBHOOK_SECRET "
                    + "REPOGUARD_GITHUB_WEBHOOK_SECRET_FILE"
            )
            .contains("config --environment")
            .contains("rewrite_env true \"$backup_directory\"")
            .contains("rewrite_env false \"$backup_directory\"")
            .contains("chmod 444 \"$secret_path\"")
            .contains("schedule_compose_secret_mode \"$file_key\" \"$secret_path\"")
            .contains("Normalized production secret files for non-root Compose bind mounts.")
            .contains("Prepared production secret files without removing legacy fallback keys.")
            .contains("Removed legacy inline secret keys after successful deployment verification.")
            .doesNotContain(
                "\n  secret_path=\"$2\"\n",
                "/dev/urandom",
                "date +%s%N"
            );
        assertThat(deploy)
            .contains(
                "INITIALIZE_MISSING_ENCRYPTION_SALT requires "
                    + "MIGRATE_LEGACY_SECRET_FILES=true."
            )
            .contains(
                "INITIALIZE_MISSING_ENCRYPTION_SALT="
                    + "\"$INITIALIZE_MISSING_ENCRYPTION_SALT\""
            );
    }

    @Test
    void mysqlBackupConsumesTheRootPasswordFileWithoutPuttingTheSecretInArguments() throws IOException {
        String script = read(repositoryRoot().resolve("scripts/backup-prod-mysql.sh"));

        assertThat(script)
            .contains("MYSQL_ROOT_PASSWORD_FILE")
            .contains("mysql_root_password=\"$(cat \"$MYSQL_ROOT_PASSWORD_FILE\")\"")
            .contains("MYSQL_PWD=\"$mysql_root_password\"")
            .doesNotContain("MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\"");
    }

    @Test
    void verifiedEncryptedBackupIsDurableInTwoImmutableTargetsBeforeLocalRetention() throws IOException {
        Path root = repositoryRoot();
        String workflow = read(root.resolve(".github/workflows/production-mysql-backup.yml"));
        String binlogWorkflow = read(root.resolve(".github/workflows/production-mysql-binlog-archive.yml"));
        String uploader = read(root.resolve("scripts/upload-immutable-backup-object.sh"));

        assertThat(workflow)
            .contains(
                "cron: '30 */6 * * *'",
                "permissions:",
                "id-token: write",
                "aws-actions/configure-aws-credentials@e1253824e5c10ff9df46874f81ed3ec929e19cfd",
                "Copy verified encrypted backup off host",
                "Persist to immutable primary and replica storage",
                "steps.object_storage.outputs.verified == 'true'"
            )
            .doesNotContain("actions/upload-artifact@");
        assertThat(workflow.indexOf("Persist to immutable primary and replica storage"))
            .isLessThan(workflow.indexOf("Apply local seven-backup retention"));
        assertThat(binlogWorkflow.indexOf("Persist to immutable primary and replica storage"))
            .isLessThan(binlogWorkflow.indexOf("Acknowledge durable PITR archive"));
        assertThat(uploader).contains(
            "--server-side-encryption aws:kms",
            "--object-lock-mode COMPLIANCE",
            "REPOGUARD_BACKUP_REPLICA_BUCKET",
            "IMMUTABLE_REPLICATION_VERIFIED=true"
        );
    }

    private int rabbitConsumerTimeout() throws IOException {
        String config = read(repositoryRoot().resolve("config/rabbitmq/rabbitmq.conf"));
        Matcher matcher = Pattern.compile(
            "(?m)^\\s*consumer_timeout\\s*=\\s*(\\d+)\\s*$"
        ).matcher(config);
        assertThat(matcher.find()).as("RabbitMQ consumer_timeout must be configured").isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private int interpolationDefault(Object value, String key) {
        Matcher matcher = Pattern.compile(
            "\\$\\{" + Pattern.quote(key) + ":-(\\d+)}"
        ).matcher(String.valueOf(value));
        assertThat(matcher.find()).as("%s must declare a numeric compose default", key).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private Map<String, Object> services() throws IOException {
        return map(yaml(repositoryRoot().resolve("docker-compose.prod.yml")).get("services"));
    }

    private Map<String, Object> bindMount(Map<String, Object> service, String source) {
        for (Object volume : list(service.get("volumes"))) {
            if (volume instanceof Map<?, ?> candidate && source.equals(candidate.get("source"))) {
                return map(candidate);
            }
        }
        fail("Missing bind mount source " + source);
        throw new IllegalStateException("unreachable");
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve(".git"))
                && Files.isDirectory(current.resolve("repoguard-backend"))) {
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
    private List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
