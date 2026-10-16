#!/usr/bin/env python3
"""Apply an explicitly approved Loki image to verified restored volumes."""
import argparse
import copy
from datetime import datetime, timezone
try:
    import fcntl
except ImportError:
    fcntl = None
import hashlib
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys
import time
import urllib.parse
import urllib.request


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


release = module('loki_release', 'loki-release.py')
snapshot = module('loki_snapshot', 'loki_snapshot.py')
identity = module('release_manifest', 'release-manifest.py')
require = release.require
ROOT = Path('/opt/repoguard')
KEY = Path('/etc/repoguard/backup-password')


def docker(*args, timeout=45):
    return release.command(['docker', *args], timeout=timeout)


def ensure_directory(path):
    path = Path(path)
    require(path.is_relative_to(ROOT) and ROOT.resolve() == ROOT, 'unsafe_release_directory')
    current = ROOT
    for part in path.relative_to(ROOT).parts:
        current = current / part
        if not current.exists():
            current.mkdir(mode=0o700)
        require(current.is_dir() and not current.is_symlink() and current.stat().st_uid == 0,
            'unsafe_release_directory')
    return path


def write_record(path, value):
    path = Path(path)
    require(path.parent.resolve() == path.parent and path.parent.stat().st_uid == 0,
        'unsafe_audit_directory')
    temporary = path.with_name(path.name + '.writing')
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'w') as output:
        json.dump(value, output, sort_keys=True, indent=2)
        output.flush()
        os.fsync(output.fileno())
    require(not path.is_symlink(), 'unsafe_audit_file')
    os.replace(temporary, path)


def request(base, path):
    parsed = urllib.parse.urlparse(base)
    address = ipaddress.ip_address(parsed.hostname)
    require(parsed.scheme == 'http' and (address.is_loopback or address.is_private), 'unexpected_health_endpoint')
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(base + path, timeout=4) as response:
        raw = response.read(2 * 1024 * 1024 + 1)
        require(len(raw) <= 2 * 1024 * 1024, 'health_response_budget_exceeded')
        return response.status, raw


def wait_for(check, description, seconds=120):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            if check():
                return
        except (OSError, ValueError, KeyError):
            pass
        time.sleep(2)
    raise ValueError(description + '_timeout')


def rules_loaded(base):
    status, body = request(base, '/prometheus/api/v1/rules')
    document = json.loads(body)
    loaded = {rule['name'] for group in document['data']['groups'] for rule in group['rules']}
    return status == 200 and document['status'] == 'success' and release.RUNTIME_RULES <= loaded


def log_fingerprint(base, window):
    query = urllib.parse.urlencode({'query': '{container=~"repoguard-backend|repoguard-backend-worker"} |= "operation=runtime_metric_snapshot metric=heartbeat"',
        'start': window['start'], 'end': window['end'], 'direction': 'backward', 'limit': 200})
    status, body = request(base, '/loki/api/v1/query_range?' + query)
    document = json.loads(body)
    require(status == 200 and document['status'] == 'success', 'historical_log_query_failed')
    values = sorted((json.dumps(stream['stream'], sort_keys=True), value[0], value[1])
        for stream in document['data']['result'] for value in stream['values'])
    require(values, 'natural_heartbeat_sample_missing')
    return {'rows': len(values), 'sha256': hashlib.sha256(json.dumps(values, separators=(',', ':')).encode()).hexdigest(),
        'sampleHashes': [hashlib.sha256(json.dumps(value, separators=(',', ':')).encode()).hexdigest() for value in values]}


def retains_logs(current, expected):
    return bool(expected['sampleHashes']) and set(expected['sampleHashes']) <= set(current['sampleHashes'])


def container_baseline():
    identifiers = docker('ps', '-aq', '--no-trunc').decode().split()
    require(1 <= len(identifiers) <= 100, 'unexpected_container_inventory_size')
    entries = json.loads(docker('inspect', *identifiers))
    return {item['Name']: {'id': item['Id'], 'image': item['Image'], 'state': item['State']['Status'],
        'health': item['State'].get('Health', {}).get('Status')} for item in entries
        if item['Name'] not in ('/repoguard-loki', '/repoguard-alloy')}


def available_memory():
    values = {line.split(':', 1)[0]: int(line.split()[1]) * 1024
        for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith('MemAvailable:')}
    return values.get('MemAvailable', 0)


def interrupted_operation(signum, frame):
    # Give the existing failure path a chance to restore service even if the
    # SSH client disconnects or the workflow is cancelled during maintenance.
    for name in ('SIGTERM', 'SIGINT', 'SIGHUP'):
        if hasattr(signal, name):
            signal.signal(getattr(signal, name), signal.SIG_IGN)
    raise InterruptedError('production_operation_interrupted')


def overlay_document(plan, clones, image):
    return {'services': {'loki': {'image': image}}, 'volumes': {
        plan['volumes'][role]['logicalName']: {'external': True, 'name': clones[role]['name']}
        for role in ('loki', 'alloy')}}


def validate_compose_change(before, after, plan, clones, image):
    expected = copy.deepcopy(before)
    expected['services']['loki']['image'] = image
    for role in ('loki', 'alloy'):
        expected['volumes'][plan['volumes'][role]['logicalName']]['name'] = clones[role]['name']
    require(expected == after, 'compose_change_exceeds_reviewed_scope')


class Deployment:
    def __init__(self, args):
        require(os.name == 'posix' and os.geteuid() == 0 and re.fullmatch(r'[1-9][0-9]{4,19}-[1-9][0-9]{0,3}', args.release_id),
            'invalid_release_context')
        self.args = args
        self.host = release.DockerHost()
        self.manifest = release.source_from_args(args)
        self.reference = self.manifest['image']['repository'] + '@' + self.manifest['image']['digest']
        self.owner = 'loki-security-' + args.release_id
        self.stage = ROOT / '.deploy-state' / self.owner
        self.record = {'formatVersion': 1, 'releaseId': args.release_id, 'source': self.manifest['source'],
            'manifestSha256': args.manifest_sha, 'candidateImage': self.manifest['image'],
            'state': 'starting', 'productionWrites': True, 'events': []}
        self.plan = None
        self.clones = {}
        self.canary = None
        self.network = None
        self.switch_started = False
        self.stopped = False

    def audit(self, state, **fields):
        self.record.update(state=state, **fields)
        self.record['events'].append({'state': state, 'atUtc': datetime.now(timezone.utc).isoformat()})
        write_record(self.stage / 'result.json', self.record)

    def verify_image(self, reference, expected):
        normalized = identity.inspection('', reference)
        require(normalized['Id'] == expected, 'pulled_image_identity_mismatch')
        metadata = json.loads(docker('image', 'inspect', reference))[0]
        require(metadata['Config']['User'] == '10001', 'pulled_image_user_mismatch')
        return normalized

    def frozen(self):
        for role, identifier in self.plan['containerIds'].items():
            state = self.host.inspect(identifier)['State']
            require(state['Status'] == 'exited' and state['ExitCode'] == 0 and not state.get('OOMKilled'),
                'writer_did_not_stop_cleanly')
            users = docker('ps', '-q', '--filter', 'volume=' + self.plan['volumes'][role]['name']).decode().split()
            require(not users, 'another_volume_writer_is_running')
        return True

    def create_clone(self, role):
        name = self.owner + '-' + role
        existing = subprocess.run(['docker', 'volume', 'inspect', name], capture_output=True, timeout=15)
        require(existing.returncode != 0, 'clone_volume_already_exists')
        docker('volume', 'create', '--label', 'repoguard.loki-release-owner=' + self.owner, name)
        volume = self.host.volume(name)
        path = Path(volume['Mountpoint'])
        require(volume['Labels'].get('repoguard.loki-release-owner') == self.owner
            and volume['Driver'] == 'local' and str(path).startswith('/var/lib/docker/volumes/')
            and path.resolve() == path and path.is_dir() and not any(path.iterdir()), 'unsafe_clone_volume')
        return {'name': name, 'mountpoint': str(path)}

    def remove_canary(self, require_clean=True):
        clean = True
        if self.canary:
            metadata = self.host.inspect(self.canary)
            require(metadata['Config']['Labels'].get('repoguard.loki-release-owner') == self.owner,
                'unexpected_canary_owner')
            if metadata['State']['Status'] == 'running':
                docker('stop', '--time', '45', self.canary, timeout=75)
            stopped = self.host.inspect(self.canary)['State']
            clean = stopped['Status'] == 'exited' and stopped['ExitCode'] == 0 and not stopped.get('OOMKilled')
            docker('rm', self.canary)
            self.canary = None
        if self.network:
            metadata = json.loads(docker('network', 'inspect', self.network))[0]
            require(metadata['Labels'].get('repoguard.loki-release-owner') == self.owner, 'unexpected_canary_network_owner')
            docker('network', 'rm', self.network)
            self.network = None
        require(not require_clean or clean, 'canary_did_not_stop_cleanly')

    def canary_check(self, window, expected):
        network = self.owner + '-check-network'
        docker('network', 'create', '--internal', '--label', 'repoguard.loki-release-owner=' + self.owner, network)
        self.network = network
        canary = self.owner + '-check'
        baseline = self.host.inspect(self.plan['containerIds']['loki'])
        args = ['run', '-d', '--name', canary, '--network', self.network, '--read-only',
            '--memory', '384m', '--cpus', '0.50', '--user', '10001', '--cap-drop', 'ALL',
            '--security-opt', 'no-new-privileges', '--tmpfs', '/tmp:rw,noexec,nosuid,size=32m',
            '--label', 'repoguard.loki-release-owner=' + self.owner,
            '-v', self.clones['loki']['name'] + ':/loki']
        for mount in baseline['Mounts']:
            if mount['Type'] == 'bind':
                require(not mount['RW'] and mount['Destination'] in ('/etc/loki/loki.yml', '/etc/loki/runtime-rules'),
                    'unexpected_canary_bind_mount')
                args += ['-v', mount['Source'] + ':' + mount['Destination'] + ':ro']
        if baseline['Config'].get('WorkingDir'):
            args += ['--workdir', baseline['Config']['WorkingDir']]
        try:
            docker(*args, self.reference, *baseline['Config']['Cmd'])
            self.canary = canary
        except BaseException:
            existing = subprocess.run(['docker', 'inspect', canary], capture_output=True, timeout=15)
            if existing.returncode == 0:
                self.canary = canary
            raise
        metadata = self.host.inspect(self.canary)
        require(not any(binding for binding in (metadata['NetworkSettings']['Ports'] or {}).values()), 'canary_published_ports')
        base = 'http://' + metadata['NetworkSettings']['Networks'][self.network]['IPAddress'] + ':3100'
        wait_for(lambda: request(base, '/ready')[0] == 200, 'restored_candidate_readiness')
        wait_for(lambda: rules_loaded(base), 'restored_candidate_rules')
        wait_for(lambda: retains_logs(log_fingerprint(base, window), expected), 'restored_historical_logs')
        self.remove_canary()

    def apply_overlay(self, image, name):
        files = [Path(item['path']) for item in self.plan['composeFiles']]
        for item in self.plan['composeFiles']:
            require(hashlib.sha256(release.reviewed_file(item['path'], ROOT).read_bytes()).hexdigest() == item['sha256'],
                'production_compose_changed_during_release')
        overlay = self.stage / name
        document = overlay_document(self.plan, self.clones, image)
        if overlay.exists():
            require(release.read_json(overlay) == document and overlay.stat().st_uid == 0
                and stat.S_IMODE(overlay.stat().st_mode) == 0o600, 'release_overlay_already_exists_with_other_content')
        else:
            write_record(overlay, document)
        before = json.loads(self.host.compose(ROOT, files, 'config', '--format', 'json'))
        after = json.loads(self.host.compose(ROOT, [*files, overlay], 'config', '--format', 'json'))
        validate_compose_change(before, after, self.plan, self.clones, image)
        self.switch_started = True
        self.host.compose(ROOT, [*files, overlay], 'up', '-d', '--pull', 'never', '--no-build', '--no-deps', 'loki', 'alloy')
        return str(overlay)

    def verify_active(self, image, window, fingerprint):
        for role, target in [('loki', '/loki'), ('alloy', '/var/lib/alloy/data')]:
            metadata = self.host.inspect('repoguard-' + role)
            expected_image = image if role == 'loki' else self.plan['baselineImages']['alloy']
            require(metadata['Config']['Image'] == expected_image and metadata['State']['Status'] == 'running'
                and not metadata['State'].get('OOMKilled'), 'unexpected_active_' + role)
            mount = release.data_mount(metadata, target, self.host)
            require(mount['name'] == self.clones[role]['name'], 'active_volume_identity_mismatch')
        metadata = self.host.inspect('repoguard-loki')
        require(metadata['HostConfig']['Memory'] == self.plan['memoryLimitBytes']
            and metadata['HostConfig']['NanoCpus'] == self.plan['cpuLimitNanoCpusPreserved'], 'active_resource_limits_changed')
        base = 'http://127.0.0.1:3100'
        wait_for(lambda: request(base, '/ready')[0] == 200, 'active_loki_readiness')
        wait_for(lambda: rules_loaded(base), 'active_loki_rules')
        wait_for(lambda: retains_logs(log_fingerprint(base, window), fingerprint), 'active_historical_logs')
        wait_for(lambda: request('http://127.0.0.1:9080', '/-/ready')[0] == 200, 'active_alloy_readiness')
        wait_for(lambda: request('http://127.0.0.1:9080', '/-/healthy')[0] == 200, 'active_alloy_health')
        require(container_baseline() == self.protected, 'other_container_state_changed')

    def rollback_active(self, window, fingerprint):
        self.remove_canary(require_clean=False)
        # The old binary uses the restored active data and cursor, retaining
        # logs accepted during the candidate period. Original volumes stay frozen.
        for role in ('alloy', 'loki'):
            probe = subprocess.run(['docker', 'inspect', 'repoguard-' + role], capture_output=True, timeout=15)
            if probe.returncode != 0:
                continue
            metadata = json.loads(probe.stdout)[0]
            allowed = (self.plan['baselineImages'][role], self.reference) if role == 'loki' else (self.plan['baselineImages'][role],)
            require(metadata['Config']['Labels'].get('com.docker.compose.project') == 'repoguard-observability'
                and metadata['Config']['Image'] in allowed,
                'unexpected_container_during_rollback')
            if metadata['State']['Status'] == 'running':
                docker('stop', '--time', '45', metadata['Id'], timeout=75)
        overlay = self.apply_overlay(self.plan['baselineImages']['loki'], 'rollback.compose.json')
        self.verify_active(self.plan['baselineImages']['loki'], window, fingerprint)
        self.audit('rolled_back', rollbackOverlay=overlay, originalVolumesPreserved=True,
            rollbackUsesRestoredActiveVolumes=True, otherContainersUnchanged=True)

    def upgrade(self):
        require(self.args.confirmation == 'UPGRADE_LOKI_SECURITY', 'explicit_upgrade_confirmation_required')
        require(not (self.stage / 'result.json').exists(), 'release_already_started; inspect_existing_journal')
        ensure_directory(self.stage)
        self.plan = release.production_preflight(self.manifest)
        self.protected = container_baseline()
        self.audit('preflight_passed', plan=self.plan)
        require(available_memory() >= 768 * 1024 * 1024, 'insufficient_memory_headroom')
        docker('pull', self.reference, timeout=240)
        self.verify_image(self.reference, self.manifest['image']['imageId'])
        backup_directory = ensure_directory(ROOT / 'backups/loki-security' / self.args.release_id)
        self.audit('image_verified')
        window = None
        fingerprint = None
        try:
            # Stop the collector first so its real cursor and acknowledged
            # delivery state are persisted before the log store is frozen.
            self.stopped = True
            docker('stop', '--time', '45', self.plan['containerIds']['alloy'], timeout=75)
            now = time.time_ns()
            window = {'start': str(now - 300 * 1_000_000_000), 'end': str(now)}
            fingerprint = log_fingerprint('http://127.0.0.1:3100', window)
            docker('stop', '--time', '45', self.plan['containerIds']['loki'], timeout=75)
            self.frozen()
            self.audit('writers_frozen', historicalWindow=window, historicalFingerprint=fingerprint)
            saved = snapshot.create_snapshot({role: item['mountpoint'] for role, item in self.plan['volumes'].items()},
                backup_directory / 'loki-alloy.tar.gz.enc', KEY, self.frozen)
            self.audit('backup_encrypted', backup=saved)
            self.clones = {role: self.create_clone(role) for role in ('loki', 'alloy')}
            self.audit('restore_destinations_created', clonedVolumes=self.clones)
            restored = snapshot.restore_snapshot(saved, {role: item['mountpoint'] for role, item in self.clones.items()}, KEY)
            saved['restoreVerified'] = True
            self.audit('backup_restore_verified', backup=saved, restore=restored)
            self.canary_check(window, fingerprint)
            self.frozen()
            self.audit('restored_candidate_verified')
            overlay = self.apply_overlay(self.reference, 'candidate.compose.json')
            self.verify_active(self.reference, window, fingerprint)
            self.verify_image(self.reference, self.manifest['image']['imageId'])
            self.audit('succeeded', activeOverlay=overlay, originalVolumesPreserved=True, otherContainersUnchanged=True,
                runtimeRulesVerified=True, historicalLogsVerified=True)
        except BaseException as error:
            self.audit('failed', errorType=type(error).__name__, errorCode=str(error) if isinstance(error, ValueError) else 'operation_failed')
            try:
                if self.switch_started:
                    self.rollback_active(window, fingerprint)
                elif self.stopped:
                    try:
                        self.remove_canary(require_clean=False)
                    except BaseException as cleanup_error:
                        self.record['canaryCleanupErrorType'] = type(cleanup_error).__name__
                    docker('start', self.plan['containerIds']['loki'])
                    wait_for(lambda: request('http://127.0.0.1:3100', '/ready')[0] == 200, 'original_loki_restart')
                    docker('start', self.plan['containerIds']['alloy'])
                    wait_for(lambda: request('http://127.0.0.1:9080', '/-/ready')[0] == 200, 'original_alloy_restart')
                    require(container_baseline() == self.protected, 'other_container_state_changed')
                    self.audit('original_services_restarted', originalVolumesPreserved=True, otherContainersUnchanged=True)
            except BaseException as rollback_error:
                self.audit('rollback_failed', rollbackErrorType=type(rollback_error).__name__, allVolumesAndBackupRetained=True)
            raise
        finally:
            if self.canary or self.network:
                try:
                    self.remove_canary(require_clean=False)
                except BaseException as cleanup_error:
                    self.record['canaryCleanupErrorType'] = type(cleanup_error).__name__
                    write_record(self.stage / 'result.json', self.record)
        return self.record

    def rollback(self):
        require(self.args.confirmation == 'ROLLBACK_LOKI_SECURITY', 'explicit_rollback_confirmation_required')
        path = self.stage / 'result.json'
        prior = release.read_json(path)
        require(path.stat().st_uid == 0 and stat.S_IMODE(path.stat().st_mode) == 0o600,
            'untrusted_release_journal')
        require(prior.get('formatVersion') == 1 and prior.get('manifestSha256') == self.args.manifest_sha
            and prior.get('candidateImage') == self.manifest['image'] and prior.get('source') == self.manifest['source']
            and prior.get('releaseId') == self.args.release_id and prior.get('state') in ('succeeded', 'rolled_back', 'rollback_failed'),
            'journal_does_not_match_approved_release')
        self.record = prior
        self.plan = prior['plan']
        self.clones = prior['clonedVolumes']
        self.protected = container_baseline()
        for role, clone in self.clones.items():
            require(role in ('loki', 'alloy') and clone['name'] == self.owner + '-' + role,
                'unexpected_rollback_volume')
            volume = self.host.volume(clone['name'])
            require(volume['Labels'].get('repoguard.loki-release-owner') == self.owner
                and volume['Mountpoint'] == clone['mountpoint'], 'rollback_volume_ownership_changed')
        window, fingerprint = prior['historicalWindow'], prior['historicalFingerprint']
        current = self.host.inspect('repoguard-loki')['Config']['Image']
        require(current in (self.reference, self.plan['baselineImages']['loki']), 'unexpected_rollback_image')
        self.verify_active(current, window, fingerprint)
        if current == self.plan['baselineImages']['loki']:
            self.audit('rolled_back', alreadyRolledBack=True, originalVolumesPreserved=True)
        else:
            self.audit('rollback_requested')
            self.switch_started = True
            try:
                self.rollback_active(window, fingerprint)
            except BaseException as error:
                self.audit('rollback_failed', rollbackErrorType=type(error).__name__, allVolumesAndBackupRetained=True)
                raise
        return self.record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('upgrade', 'rollback'))
    release.source_arguments(parser)
    parser.add_argument('--run-attempt', type=int, required=True)
    parser.add_argument('--manifest', required=True)
    parser.add_argument('--manifest-sha', required=True)
    parser.add_argument('--release-id', required=True)
    parser.add_argument('--confirmation', required=True)
    args = parser.parse_args()
    expected = 'UPGRADE_LOKI_SECURITY' if args.action == 'upgrade' else 'ROLLBACK_LOKI_SECURITY'
    require(args.confirmation == expected, 'explicit_production_confirmation_required')
    require(os.name == 'posix' and fcntl is not None, 'linux_production_host_required')
    require(ROOT.resolve() == ROOT and ROOT.is_dir(), 'unexpected_deployment_root')
    descriptor = os.open(ROOT / '.deploy.lock', os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        for name in ('SIGTERM', 'SIGINT', 'SIGHUP'):
            if hasattr(signal, name):
                signal.signal(getattr(signal, name), interrupted_operation)
        deployment = Deployment(args)
        result = deployment.upgrade() if args.action == 'upgrade' else deployment.rollback()
        print(json.dumps(result))
    finally:
        os.close(descriptor)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, subprocess.TimeoutExpired) as error:
        print(json.dumps({'state': 'failed', 'reason': str(error) if isinstance(error, ValueError)
            else type(error).__name__}), file=sys.stderr)
        sys.exit(2)
