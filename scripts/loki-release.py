#!/usr/bin/env python3
"""Verify Loki source releases and prepare bounded production operations."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile

SOURCE_WORKFLOW = '.github/workflows/production-observability-security.yml'
VERSION = '3.7.8-repoguard.1'
UPSTREAM = '09e6ce2ff1bdc19763a10265b870c86f51c98655'
RUNTIME_CONFIG_SHA = 'fd92c23ef93df13499bc10537ee9561c26af0fd68e43899e1203230a13b4f2a4'
RUNTIME_RULES_SHA = 'a18d3d0b9276ddab80fa961d14df06eba4a60bd46e5ce106a1f42074aed0b2d5'
BASELINE_IMAGE = 'grafana/loki:3.7.8@sha256:1107dd5274e0ada47e42472b7a7e71f3b2a2fe878878108f3e2f9e51528f0193'
ALLOY_IMAGE = 'grafana/alloy:v1.18.0@sha256:491b0578c04983fd54fe99b587b6fab4404dc46d0dc16677bd6b00cc1140b308'
RUNTIME_RULES = {'RepoGuardApiHeartbeatMissing', 'RepoGuardWorkerHeartbeatMissing',
    'RepoGuardUsageSnapshotStale', 'RepoGuardUncertainUsageAged',
    'RepoGuardBudgetAdmissionDenied', 'RepoGuardQueueWaitHigh'}
DIGEST = re.compile(r'sha256:[a-f0-9]{64}\Z')
SHA = re.compile(r'[a-f0-9]{40}\Z')
REPOSITORY = re.compile(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\Z')


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def positive(value):
    return type(value) is int and value > 0


def read_json(path):
    path = Path(path)
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 1024 * 1024,
        'unsafe_manifest_file')
    return json.loads(path.read_text(encoding='utf-8'))


def write_json(path, value):
    Path(path).write_text(json.dumps(value, sort_keys=True, indent=2) + '\n', encoding='utf-8')


def validate_source(manifest, repository, source_sha, run_id, run_attempt):
    require(REPOSITORY.fullmatch(repository or '') and SHA.fullmatch(source_sha or ''), 'invalid_source_identity')
    require(positive(run_id) and positive(run_attempt), 'invalid_source_run')
    require(isinstance(manifest, dict) and type(manifest.get('formatVersion')) is int
        and manifest['formatVersion'] == 1 and manifest.get('version') == VERSION, 'unsupported_loki_release')
    require(manifest.get('source') == {'repository': repository, 'workflow': SOURCE_WORKFLOW,
        'gitSha': source_sha, 'ref': 'refs/heads/main', 'runId': run_id, 'runAttempt': run_attempt},
        'source_identity_mismatch')
    source = manifest['source']
    require(positive(source['runId']) and positive(source['runAttempt']), 'invalid_manifest_run')
    require(manifest.get('upstreamGitSha') == UPSTREAM, 'unapproved_upstream')
    image = manifest.get('image')
    require(isinstance(image, dict) and image.get('repository') == 'ghcr.io/' + repository.lower() + '-loki'
        and image.get('platform') == 'linux/amd64', 'unexpected_image_target')
    require(all(isinstance(image.get(key), str) and DIGEST.fullmatch(image[key]) for key in ('digest', 'imageId')),
        'invalid_image_digest')
    checks = manifest.get('checks')
    require(checks == {'highCriticalCount': 0, 'configurationValidation': 'passed',
        'persistenceUpgradeRollback': 'passed', 'independentImageTransfer': 'passed',
        'imageProvenance': 'attested', 'sbom': 'attested'} and type(checks['highCriticalCount']) is int,
        'required_checks_missing')
    runtime = manifest.get('runtime')
    require(isinstance(runtime, dict) and runtime.get('configSha256') == RUNTIME_CONFIG_SHA,
        'runtime_configuration_mismatch')
    require(type(runtime.get('memoryLimitBytes')) is int and runtime['memoryLimitBytes'] == 384 * 1024 * 1024
        and type(runtime.get('cpuLimit')) in (int, float) and runtime['cpuLimit'] == 0.5,
        'runtime_resource_gate_missing')
    rules = runtime.get('expectedRules')
    require(isinstance(rules, list) and len(rules) == len(RUNTIME_RULES)
        and all(isinstance(rule, str) for rule in rules) and set(rules) == RUNTIME_RULES, 'runtime_rule_gate_missing')
    require(isinstance(manifest.get('archiveSha256'), str)
        and re.fullmatch('[a-f0-9]{64}', manifest['archiveSha256']), 'invalid_archive_hash')
    evidence = manifest.get('evidenceSha256')
    require(isinstance(evidence, dict) and set(evidence) == {'trivy.json', 'sbom.cdx.json',
        'persistence-result.json', 'transfer-verification.json'}, 'source_evidence_missing')
    require(all(isinstance(value, str) and re.fullmatch('[a-f0-9]{64}', value) for value in evidence.values()),
        'invalid_evidence_hash')
    return manifest


def command(args, timeout=45):
    result = subprocess.run(args, capture_output=True, timeout=timeout)
    require(result.returncode == 0, 'external_command_failed:' + Path(args[0]).name)
    return result.stdout


def github(repository, endpoint):
    return json.loads(command(['gh', 'api', 'repos/' + repository + '/' + endpoint]))


def verify_attestation(subject, path, predicate, repository, sha, invocation):
    raw = command(['gh', 'attestation', 'verify', subject, '--repo', repository,
        '--signer-workflow', repository + '/' + SOURCE_WORKFLOW, '--source-digest', sha,
        '--signer-digest', sha, '--source-ref', 'refs/heads/main', '--deny-self-hosted-runners',
        '--predicate-type', predicate, '--format', 'json'], timeout=90)
    verified = json.loads(raw)
    require(isinstance(verified, list) and verified, 'attestation_missing')
    statements = []
    for item in verified:
        result = item['verificationResult']
        require(invocation in json.dumps(result['signature']['certificate']), 'certificate_run_attempt_mismatch')
        statement = result['statement']
        require(statement['predicateType'] == predicate, 'predicate_type_mismatch')
        if predicate == 'https://slsa.dev/provenance/v1':
            require(statement['predicate']['runDetails']['metadata']['invocationId'] == invocation,
                'provenance_run_attempt_mismatch')
        statements.append(statement)
    Path(path).write_bytes(raw)
    return statements


def fetch_source(args):
    require(REPOSITORY.fullmatch(args.repository or '') and SHA.fullmatch(args.source_sha or ''),
        'invalid_source_identity')
    require(positive(args.run_id), 'invalid_source_run')
    run = github(args.repository, 'actions/runs/' + str(args.run_id))
    require(run['path'] == SOURCE_WORKFLOW and run['head_repository']['full_name'] == args.repository
        and run['event'] == 'workflow_dispatch' and run['head_branch'] == 'main', 'untrusted_source_run')
    require(run['head_sha'] == args.source_sha and run['status'] == 'completed'
        and run['conclusion'] == 'success' and positive(run['run_attempt']), 'source_run_not_successful')
    jobs = github(args.repository, f'actions/runs/{args.run_id}/jobs?filter=latest&per_page=100')['jobs']
    for name in ('Validate immutable observability images', 'Isolated Loki security rebuild',
            'Verify Loki image on a fresh runner', 'Publish checked Loki source image'):
        require(any(job['name'] == name and job['conclusion'] == 'success' for job in jobs), 'source_job_not_successful')
    artifact = f'loki-source-manifest-{args.run_id}-{run["run_attempt"]}'
    artifacts = github(args.repository, f'actions/runs/{args.run_id}/artifacts?per_page=100')['artifacts']
    require(any(item['name'] == artifact and not item['expired'] for item in artifacts), 'source_artifact_missing')
    output = Path(args.output).absolute()
    require(not output.exists() and output.parent.is_dir() and output.parent.resolve() == output.parent,
        'unsafe_output_directory')
    # An isolated directory prevents stale manifests or verification output from being reused.
    with tempfile.TemporaryDirectory(prefix='loki-source-', dir=output.parent) as temporary:
        directory = Path(temporary)
        command(['gh', 'run', 'download', str(args.run_id), '--repo', args.repository,
            '--name', artifact, '--dir', str(directory)], timeout=60)
        path = directory / 'loki-source-manifest.json'
        manifest = validate_source(read_json(path), args.repository, args.source_sha, args.run_id, run['run_attempt'])
        require({item.name for item in directory.iterdir()} == {'loki-source-manifest.json'}, 'unexpected_artifact_contents')
        invocation = f'https://github.com/{args.repository}/actions/runs/{args.run_id}/attempts/{run["run_attempt"]}'
        verify_attestation(str(path), directory / 'source-attestation-verification.json',
            'https://slsa.dev/provenance/v1', args.repository, args.source_sha, invocation)
        image = manifest['image']
        reference = 'oci://' + image['repository'] + '@' + image['digest']
        for kind, predicate in [('provenance', 'https://slsa.dev/provenance/v1'), ('sbom', 'https://cyclonedx.org/bom')]:
            statements = verify_attestation(reference, directory / ('image-' + kind + '-verification.json'),
                predicate, args.repository, args.source_sha, invocation)
            for statement in statements:
                require(any(subject['digest'].get('sha256') == image['digest'].split(':')[1]
                    for subject in statement['subject']), 'signed_image_digest_mismatch')
                if kind == 'sbom':
                    bom = statement['predicate']
                    require(bom['bomFormat'] == 'CycloneDX', 'unsupported_sbom')
                    for name, version in [('stdlib', 'v1.26.9'), ('golang.org/x/net', 'v0.60.0')]:
                        require(any(component['name'] == name and component.get('version') == version
                            for component in bom['components']), 'security_dependency_missing')
        result = {'state': 'verified', 'repository': args.repository, 'gitSha': args.source_sha,
            'runId': args.run_id, 'runAttempt': run['run_attempt'], 'image': image,
            'manifestSha256': hashlib.sha256(path.read_bytes()).hexdigest(),
            'sourceManifestSignatureVerified': True, 'imageProvenanceSignatureVerified': True,
            'imageSbomSignatureVerified': True, 'productionWrites': 0}
        write_json(directory / 'verification-result.json', result)
        # Reserve the destination atomically. Verification is published last;
        # an existing path is never replaced, even if created concurrently.
        output.mkdir(mode=0o700)
        ordered = sorted(directory.iterdir(), key=lambda item: item.name == 'verification-result.json')
        created = []
        try:
            for item in ordered:
                destination = output / item.name
                with destination.open('xb') as target:
                    created.append(destination)
                    target.write(item.read_bytes())
        except BaseException:
            for item in created:
                item.unlink()
            if not any(output.iterdir()):
                output.rmdir()
            raise
    print(json.dumps(result))


class DockerHost:
    """Keep Docker operations behind one bounded, non-shell transport."""
    def inspect(self, name):
        return json.loads(command(['docker', 'inspect', name]))[0]

    def volume(self, name):
        return json.loads(command(['docker', 'volume', 'inspect', name]))[0]

    def compose(self, root, files, *args):
        argv = ['docker', 'compose', '--project-directory', str(root), '-p', 'repoguard-observability',
            '--env-file', str(root / '.env')]
        for path in files:
            argv += ['-f', str(path)]
        environment = os.environ.copy()
        environment['DOCKER_SOCKET_GID'] = str(os.stat('/var/run/docker.sock').st_gid)
        result = subprocess.run([*argv, *args], capture_output=True, env=environment, timeout=30)
        require(result.returncode == 0, 'compose_operation_failed')
        return result.stdout


def reviewed_file(path, root, maximum=1024 * 1024):
    path = Path(path)
    require(path.is_absolute() and path.is_relative_to(root) and path.resolve() == path
        and path.is_file() and not path.is_symlink() and path.stat().st_size <= maximum,
        'unsafe_deployment_file')
    return path


def data_mount(container, target, host):
    mounts = [mount for mount in container['Mounts'] if mount['Destination'] == target]
    require(len(mounts) == 1 and mounts[0]['Type'] == 'volume' and mounts[0]['RW'] is True,
        'unexpected_data_mount')
    mount = mounts[0]
    volume = host.volume(mount['Name'])
    path = Path(volume['Mountpoint'])
    require(volume['Driver'] == 'local' and path.is_absolute() and path.resolve() == path
        and str(path).startswith('/var/lib/docker/volumes/') and path.is_dir()
        and str(path) == mount['Source'], 'unsafe_volume_mountpoint')
    size = int(command(['du', '-sk', '--one-file-system', str(path)]).split()[0]) * 1024
    return {'name': mount['Name'], 'mountpoint': str(path), 'target': target, 'usedBytes': size}


def source_from_args(args):
    path = Path(args.manifest)
    require(isinstance(args.manifest_sha, str) and re.fullmatch('[a-f0-9]{64}', args.manifest_sha),
        'invalid_expected_manifest_hash')
    manifest = read_json(path)
    require(hashlib.sha256(path.read_bytes()).hexdigest() == args.manifest_sha, 'manifest_checksum_mismatch')
    return validate_source(manifest, args.repository, args.source_sha, args.run_id, args.run_attempt)


def production_preflight(manifest, root=Path('/opt/repoguard'), host=None):
    """Read current state; do not pull images, create locks, stop services, or copy data."""
    host = host or DockerHost()
    root = Path(root)
    require(root == Path('/opt/repoguard') and root.resolve() == root and root.is_dir(), 'unexpected_deployment_root')
    loki = host.inspect('repoguard-loki')
    alloy = host.inspect('repoguard-alloy')
    containers = {'loki': loki, 'alloy': alloy}
    for role, expected_image, expected_user in [('loki', BASELINE_IMAGE, '10001'), ('alloy', ALLOY_IMAGE, '473:473')]:
        container = containers[role]
        labels = container['Config'].get('Labels') or {}
        require(container['State']['Status'] == 'running' and not container['State'].get('OOMKilled')
            and container['Config']['Image'] == expected_image and container['Config']['User'] == expected_user,
            'unexpected_' + role + '_baseline')
        require(labels.get('com.docker.compose.project') == 'repoguard-observability'
            and labels.get('com.docker.compose.project.working_dir') == str(root), 'unexpected_compose_project')
    require(loki['HostConfig']['Memory'] == manifest['runtime']['memoryLimitBytes'], 'production_memory_limit_changed')
    files = loki['Config']['Labels']['com.docker.compose.project.config_files'].split(',')
    require(1 <= len(files) <= 8 and len(set(files)) == len(files), 'invalid_compose_file_set')
    reviewed = [reviewed_file(name, root) for name in files]
    alloy_files = alloy['Config']['Labels']['com.docker.compose.project.config_files'].split(',')
    require(set(alloy_files) <= set(files), 'collector_compose_files_not_preserved')
    config = reviewed_file(root / 'config/observability/loki-runtime-metrics.yml', root)
    rules = reviewed_file(root / 'config/observability/runtime-rules/fake/repoguard-runtime.yml', root)
    require(hashlib.sha256(config.read_bytes()).hexdigest() == RUNTIME_CONFIG_SHA
        and hashlib.sha256(rules.read_bytes()).hexdigest() == RUNTIME_RULES_SHA, 'production_runtime_configuration_changed')
    for target, expected in [('/etc/loki/loki.yml', str(config)),
            ('/etc/loki/runtime-rules', str(rules.parent.parent))]:
        matches = [mount for mount in loki['Mounts'] if mount['Destination'] == target]
        require(len(matches) == 1 and matches[0]['Type'] == 'bind' and not matches[0]['RW']
            and matches[0]['Source'] == expected, 'runtime_bind_mount_changed')
    volumes = {'loki': data_mount(loki, '/loki', host), 'alloy': data_mount(alloy, '/var/lib/alloy/data', host)}
    require(sum(item['usedBytes'] for item in volumes.values()) <= 256 * 1024 * 1024, 'backup_exceeds_reviewed_data_budget')
    require(shutil.disk_usage(root).free >= 2 * 1024 ** 3, 'insufficient_disk_headroom')
    # Resolve secrets only inside Docker Compose; no resolved environment is emitted or saved.
    effective = json.loads(host.compose(root, reviewed, 'config', '--format', 'json'))
    for role, target in [('loki', '/loki'), ('alloy', '/var/lib/alloy/data')]:
        service = effective['services'][role]
        require(service['image'] == containers[role]['Config']['Image'], 'effective_image_differs_from_running')
        matches = [mount for mount in service['volumes'] if mount['target'] == target]
        require(len(matches) == 1 and matches[0]['type'] == 'volume', 'effective_data_mount_changed')
        logical = matches[0]['source']
        require(effective['volumes'][logical]['name'] == volumes[role]['name'], 'effective_volume_differs_from_running')
        volumes[role]['logicalName'] = logical
    key = Path('/etc/repoguard/backup-password')
    require(key.is_file() and not key.is_symlink() and key.stat().st_uid == 0
        and stat.S_IMODE(key.stat().st_mode) == 0o600 and key.stat().st_size >= 32, 'protected_backup_key_unavailable')
    return {'formatVersion': 1, 'productionWrites': 0, 'state': 'preflight_passed',
        'source': manifest['source'], 'candidateImage': manifest['image'],
        'baselineImages': {role: containers[role]['Config']['Image'] for role in containers},
        'containerIds': {role: containers[role]['Id'] for role in containers},
        'volumes': volumes, 'composeFiles': [{'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
            for path in reviewed], 'runtimeConfigSha256': RUNTIME_CONFIG_SHA, 'runtimeRulesSha256': RUNTIME_RULES_SHA,
        'memoryLimitBytes': loki['HostConfig']['Memory'], 'cpuLimitNanoCpusPreserved': loki['HostConfig']['NanoCpus'],
        'backupKeyReady': True, 'requiresExplicitProductionAuthorization': True}


def preflight_command(args):
    manifest = source_from_args(args)
    print(json.dumps(production_preflight(manifest)))


def source_arguments(parser):
    parser.add_argument('--repository', required=True)
    parser.add_argument('--source-sha', required=True)
    parser.add_argument('--run-id', type=int, required=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    fetch = commands.add_parser('fetch', help='Download and independently verify a manual-main source release')
    source_arguments(fetch)
    fetch.add_argument('--output', required=True)
    fetch.set_defaults(handler=fetch_source)
    preflight = commands.add_parser('preflight', help='Read current production metadata without stopping or modifying services')
    source_arguments(preflight)
    preflight.add_argument('--run-attempt', type=int, required=True)
    preflight.add_argument('--manifest', required=True)
    preflight.add_argument('--manifest-sha', required=True)
    preflight.set_defaults(handler=preflight_command)
    args = parser.parse_args()
    args.handler(args)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, TypeError, subprocess.TimeoutExpired, OSError) as error:
        print(json.dumps({'state': 'rejected', 'reason': str(error) if isinstance(error, ValueError)
            else type(error).__name__}), file=sys.stderr)
        sys.exit(2)
