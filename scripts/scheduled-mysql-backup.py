#!/usr/bin/env python3
"""Unattended encrypted MySQL snapshots. Never starts restore containers or stops services."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import signal
import stat
import subprocess
import sys
import threading
import time

ROOT = Path('/opt/repoguard/backups/scheduled-mysql')
PUBLIC_STATUS_ROOT = Path('/opt/repoguard/backup-status')
NAME = re.compile(r'repoguard-[0-9]{8}T[0-9]{6}Z\.sql\.gz\.enc')
MAX_BYTES = 2 * 1024**3
RESERVE_BYTES = 512 * 1024**2


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def atomic_json(path, value, mode=0o600):
    temporary = path.with_suffix('.json.partial')
    with temporary.open('w', encoding='utf-8') as stream:
        json.dump(value, stream, ensure_ascii=True, indent=2)
        stream.flush()
        os.fsync(stream.fileno())
    if mode is not None:
        temporary.chmod(mode)
    temporary.replace(path)


def write_status(state):
    atomic_json(ROOT / 'status.json', state)
    if os.environ.get('REPOGUARD_BACKUP_STATUS_PUBLISH') != 'true':
        return
    try:
        if (PUBLIC_STATUS_ROOT.resolve() != PUBLIC_STATUS_ROOT or not PUBLIC_STATUS_ROOT.is_dir()
                or PUBLIC_STATUS_ROOT.stat().st_mode & 0o022):
            raise ValueError('unsafe_public_status_directory')
        public = {'scope': 'mysql-logical-snapshot',
                  'state': state.get('state') if state.get('state') in ('running', 'success', 'failed') else 'unknown'}
        for key in ('startedAtUnix', 'finishedAtUnix', 'retained'):
            if type(state.get(key)) is int and 0 < state[key] < 2**63:
                public[key] = state[key]
        if type(state.get('storageBudgetExceeded')) is bool:
            public['storageBudgetExceeded'] = state['storageBudgetExceeded']
        if state.get('reason') in ('backup_storage_budget_exhausted', 'backup_timeout', 'service_interrupted',
                                   'archive_checksum_mismatch', 'completed_backup_not_verified', 'backup_command_failed'):
            public['reason'] = state['reason']
        if isinstance(state.get('archive'), dict) and type(state['archive'].get('bytes')) is int:
            public['archive'] = {'bytes': state['archive']['bytes']}
        atomic_json(PUBLIC_STATUS_ROOT / 'status.json', public, mode=0o644)
    except Exception:
        # Failure of this optional projection must not prevent the actual backup or expose exception data.
        print('Backup status export unavailable', file=sys.stderr)


def catalog(candidate=None):
    """Only expose completed, paired, hash-verified archives; never expose credentials."""
    entries = []
    for path in sorted(ROOT.glob('*.sql.gz.enc')):
        marker = path.with_name(path.name + '.verified.json')
        if path != candidate and not marker.is_file():
            continue
        if marker.is_symlink():
            raise ValueError('unsafe_verification_marker')
        checksum = path.with_name(path.name + '.sha256')
        if not NAME.fullmatch(path.name) or path.is_symlink() or checksum.is_symlink():
            raise ValueError('unsafe_archive_path')
        fields = checksum.read_text(encoding='ascii').split()
        if len(fields) != 2 or fields[1] != path.name or not re.fullmatch('[a-f0-9]{64}', fields[0]):
            raise ValueError('invalid_archive_checksum')
        if digest(path) != fields[0]:
            raise ValueError('archive_checksum_mismatch')
        entries.append({'name': path.name, 'bytes': path.stat().st_size, 'sha256': fields[0]})
    return entries


def run_backup():
    import fcntl  # Server operation is Linux-only; pull works on Windows too.
    os.umask(0o077)
    if ROOT.resolve() != ROOT or ROOT.is_symlink():
        raise ValueError('backup_root_must_not_be_symlink')
    ROOT.mkdir(parents=True, exist_ok=True, mode=0o700)
    if ROOT.stat().st_mode & 0o077:
        raise ValueError('backup_root_permissions_too_broad')
    with (ROOT / '.schedule.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError('backup_already_running') from None
        state = {'scope': 'mysql-logical-snapshot', 'restoreVerified': False,
                 'startedAtUnix': int(time.time()), 'state': 'running'}
        write_status(state)
        try:
            before = catalog()
            used = sum(p.stat().st_size for p in ROOT.iterdir() if p.is_file())
            if used >= MAX_BYTES or shutil.disk_usage(ROOT).free < RESERVE_BYTES:
                raise ValueError('backup_storage_budget_exhausted')
            credential = Path(os.environ['CREDENTIALS_DIRECTORY']) / 'backup-password'
            if credential.is_symlink() or credential.stat().st_mode & 0o077:
                raise ValueError('credential_permissions_too_broad')
            password = credential.read_bytes().rstrip(b'\r\n')
            if not 32 <= len(password) <= 4096 or b'\n' in password or b'\r' in password:
                raise ValueError('invalid_backup_password_format')
            env = dict(os.environ, BACKUP_ROOT=str(ROOT), VERIFY_RESTORE='false', VERIFY_MIGRATIONS='false')
            script = Path(__file__).resolve().with_name('backup-prod-mysql.sh')
            # No secrets in arguments or logs. Daily runs validate decryption/gzip via the existing script.
            process = subprocess.Popen(['bash', str(script), '--password-stdin'], env=env,
                                       stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                       stderr=subprocess.DEVNULL, start_new_session=True)
            try:
                output, _ = process.communicate(password + b'\n', timeout=900)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.communicate()
                raise ValueError('backup_timeout') from None
            finally:
                del password
            if process.returncode:
                raise ValueError('backup_command_failed')
            fields = dict(line.split('=', 1) for line in output.decode('utf-8').splitlines() if '=' in line)
            current = Path(fields.get('BACKUP_PATH', ''))
            if current.parent != ROOT or not NAME.fullmatch(current.name):
                raise ValueError('invalid_completed_backup_path')
            after = catalog(current)
            entry = next((item for item in after if item['name'] == current.name), None)
            if entry is None or entry['sha256'] != fields.get('BACKUP_SHA256'):
                raise ValueError('completed_backup_not_verified')
            if any(item['name'] == current.name for item in before):
                raise ValueError('backup_name_collision')
            atomic_json(current.with_name(current.name + '.verified.json'), entry)
            # Remove only older verified archives, after a new verified snapshot exists.
            # Bound both count and bytes, retaining at least the newest snapshot.
            retained = list(after)
            while len(retained) > 1 and (len(retained) > 7 or sum(x['bytes'] for x in retained) > MAX_BYTES):
                oldest = retained.pop(0)
                path = ROOT / oldest['name']
                if path.name == current.name or path.is_symlink() or digest(path) != oldest['sha256']:
                    raise ValueError('retention_candidate_changed')
                path.unlink()
                path.with_name(path.name + '.sha256').unlink()
                path.with_name(path.name + '.verified.json').unlink()
            state.update(state='success', archive=entry, retained=len(retained),
                         storageBudgetExceeded=sum(x['bytes'] for x in retained) > MAX_BYTES)
        except Exception as error:
            # Never serialize an arbitrary exception: it may contain subprocess arguments or secrets.
            state.update(state='failed', error=type(error).__name__)
            if isinstance(error, ValueError) and re.fullmatch('[a-z_]+', str(error)):
                state['reason'] = str(error)
            raise
        finally:
            state['finishedAtUnix'] = int(time.time())
            write_status(state)


def finalize():
    """systemd runs this after interruption, including timeout/OOM, without marking an active run failed."""
    import fcntl
    if not ROOT.is_dir() or ROOT.resolve() != ROOT:
        return
    with (ROOT / '.schedule.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return
        status = ROOT / 'status.json'
        if status.is_file():
            state = json.loads(status.read_text(encoding='utf-8'))
            if state.get('state') == 'running':
                state.update(state='failed', reason='service_interrupted', finishedAtUnix=int(time.time()))
                write_status(state)


def bounded_command(args, output, limit, timeout):
    """Stream at most limit bytes; terminate stalled or oversized SSH responses."""
    process = subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                               stderr=subprocess.DEVNULL)
    expired = threading.Event()
    def expire():
        expired.set()
        try:
            process.kill()
        except OSError:
            pass
    timer = threading.Timer(timeout, expire)
    timer.daemon = True
    timer.start()
    written = 0
    try:
        while True:
            block = process.stdout.read(min(64 * 1024, limit - written + 1))
            if not block:
                break
            if written + len(block) > limit:
                raise ValueError('remote_output_budget_exceeded')
            output.write(block)
            written += len(block)
        code = process.wait()
        if expired.is_set():
            raise ValueError('remote_command_timeout')
        if code:
            raise ValueError('remote_command_failed')
        return written
    finally:
        timer.cancel()
        if process.poll() is None:
            process.kill()
        process.wait()
        process.stdout.close()


def pull(host, destination):
    if not re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9_.@-]*', host):
        raise ValueError('invalid_ssh_host_alias')
    destination = destination.absolute()
    if destination.resolve() != destination:
        raise ValueError('local_destination_must_not_be_linked')
    destination.mkdir(parents=True, exist_ok=True)
    ssh = ['ssh', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', '-o', 'ConnectTimeout=10', host]
    response = io.BytesIO()
    bounded_command(ssh + ['python3 /opt/repoguard/scripts/scheduled-mysql-backup.py catalog'],
                    response, 64 * 1024, 120)
    entries = json.loads(response.getvalue())
    if not isinstance(entries, list) or len(entries) > 100:
        raise ValueError('invalid_catalog')
    names, total = set(), 0
    for entry in entries:
        if not isinstance(entry, dict) or not all(key in entry for key in ('name', 'bytes', 'sha256')):
            raise ValueError('invalid_catalog_entry')
        name, size, expected = entry['name'], entry['bytes'], entry['sha256']
        if (not isinstance(name, str) or not isinstance(expected, str)
                or not NAME.fullmatch(name) or not re.fullmatch('[a-f0-9]{64}', expected) or name in names):
            raise ValueError('invalid_catalog_entry')
        if type(size) is not int or not 0 < size <= MAX_BYTES:
            raise ValueError('archive_exceeds_pull_budget')
        names.add(name)
        total += size
        if total > MAX_BYTES:
            raise ValueError('catalog_exceeds_pull_budget')
    copied = 0
    for entry in entries:
        name, size, expected = entry['name'], entry['bytes'], entry['sha256']
        target = destination / name
        if target.is_symlink():
            raise ValueError('local_archive_symlink')
        if target.exists():
            if target.stat().st_size != size or digest(target) != expected:
                raise ValueError('local_archive_mismatch')
            continue
        if shutil.disk_usage(destination).free < size + RESERVE_BYTES:
            raise ValueError('insufficient_local_space')
        metadata = target.with_name(name + '.json')
        if metadata.exists() or metadata.is_symlink():
            raise ValueError('local_metadata_already_exists')
        temporary = target.with_name(name + '.partial')
        owned = False
        try:
            with temporary.open('xb') as stream:
                owned = True
                bounded_command(ssh + ['cat -- ' + (ROOT / name).as_posix()], stream, size, 300)
                stream.flush()
                os.fsync(stream.fileno())
            if temporary.stat().st_size != size or digest(temporary) != expected:
                raise ValueError('download_checksum_mismatch')
            # Hard-link publication is atomic and fails if another process created the target.
            # Unsupported filesystems fail closed rather than falling back to an overwrite.
            os.link(temporary, target)
            with metadata.open('x', encoding='utf-8') as stream:
                json.dump({'name': name, 'bytes': size, 'sha256': expected}, stream)
            copied += 1
        finally:
            if owned:
                temporary.unlink(missing_ok=True)
    print(json.dumps({'verified': len(entries), 'copied': copied}))


def backup_status(max_age_hours=30):
    """Read only the bounded execution record; never decrypt, hash archives or inspect credentials."""
    now = int(time.time())
    result = {'scope': 'mysql-logical-snapshot', 'checkedAtUnix': now,
              'maxAgeHours': max_age_hours, 'restoreVerified': False,
              'archiveIntegrityChecked': False, 'processLivenessChecked': False,
              'timerEnabledChecked': False}
    path = ROOT / 'status.json'
    try:
        if ROOT.resolve() != ROOT:
            raise ValueError('unsafe_status_root')
        info = path.lstat()
        if not stat.S_ISREG(info.st_mode) or getattr(info, 'st_file_attributes', 0) & 0x400:
            raise ValueError('unsafe_status_file')
        with path.open('rb') as stream:
            raw = stream.read(64 * 1024 + 1)
        if len(raw) > 64 * 1024:
            raise ValueError('status_size_exceeded')
        state = json.loads(raw)
        if not isinstance(state, dict) or state.get('scope') != 'mysql-logical-snapshot':
            raise ValueError('invalid_status_record')
        phase, started = state.get('state'), state.get('startedAtUnix')
        if phase not in ('running', 'failed', 'success') or type(started) is not int or not 0 < started <= now:
            raise ValueError('invalid_status_record')
        result.update(recordedState=phase, startedAtUnix=started)
        if phase == 'running':
            result.update(ageSeconds=now - started,
                          status='RUN_OVERRUN' if now - started > 16 * 60 else 'RUNNING_RECORDED')
            return result, 2 if result['status'] == 'RUN_OVERRUN' else 1
        finished = state.get('finishedAtUnix')
        if type(finished) is not int or not started <= finished <= now:
            raise ValueError('invalid_status_record')
        result.update(finishedAtUnix=finished, ageSeconds=now - finished)
        if phase == 'failed':
            reasons = {'backup_storage_budget_exhausted', 'credential_permissions_too_broad',
                       'invalid_backup_password_format', 'backup_timeout', 'backup_command_failed',
                       'invalid_completed_backup_path', 'completed_backup_not_verified',
                       'backup_name_collision', 'retention_candidate_changed', 'service_interrupted',
                       'unsafe_verification_marker', 'unsafe_archive_path', 'invalid_archive_checksum',
                       'archive_checksum_mismatch'}
            result.update(status='FAILED', reason=state.get('reason') if state.get('reason') in reasons else 'unspecified_failure')
            return result, 2
        archive = state.get('archive')
        if (not isinstance(archive, dict) or not isinstance(archive.get('name'), str)
                or not NAME.fullmatch(archive['name']) or type(archive.get('bytes')) is not int
                or archive['bytes'] <= 0 or not isinstance(archive.get('sha256'), str)
                or not re.fullmatch('[a-f0-9]{64}', archive['sha256'])
                or type(state.get('storageBudgetExceeded')) is not bool
                or type(state.get('retained')) is not int or not 1 <= state['retained'] <= 100):
            raise ValueError('invalid_status_record')
        result.update(archive={key: archive[key] for key in ('name', 'bytes', 'sha256')},
                      retained=state['retained'], storageBudgetExceeded=state['storageBudgetExceeded'])
        result['status'] = ('STORAGE_LIMIT' if state['storageBudgetExceeded'] else
                            'STALE' if now - finished > max_age_hours * 3600 else 'SUCCESS_RECORDED')
        return result, 0 if result['status'] == 'SUCCESS_RECORDED' else 2
    except FileNotFoundError:
        result['status'] = 'MISSING'
    except (ValueError, TypeError, OSError):
        result['status'] = 'UNREADABLE_OR_INVALID'
    return result, 2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['run', 'catalog', 'pull', 'finalize', 'status'])
    parser.add_argument('--max-age-hours', type=int, default=30, help='Status freshness threshold, 1 to 168 hours (default: 30)')
    parser.add_argument('--host', help='Existing OpenSSH config alias; strict host verification is required')
    parser.add_argument('--destination', type=Path)
    args = parser.parse_args()
    if not 1 <= args.max_age_hours <= 168:
        parser.error('--max-age-hours must be between 1 and 168')
    if args.command == 'status':
        result, code = backup_status(args.max_age_hours)
        print(json.dumps(result))
        return code
    elif args.command == 'run':
        run_backup()
    elif args.command == 'finalize':
        finalize()
    elif args.command == 'catalog':
        if ROOT.resolve() != ROOT or not ROOT.is_dir():
            raise ValueError('backup_root_unavailable')
        print(json.dumps(catalog()))
    else:
        if not args.host or not args.destination:
            parser.error('pull requires --host and --destination')
        pull(args.host, args.destination)


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as error:
        print('Backup operation failed: ' + type(error).__name__, file=sys.stderr)
        sys.exit(1)
