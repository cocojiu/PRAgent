#!/usr/bin/env python3
"""Opt-in encrypted MySQL 8.0 local-clone backup and isolated recovery rehearsal.

No plugin installation, service restart, automatic retry, or production restore.
Only a completed clone is archived; restored pages are checked before mysqld opens them.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import time
import uuid

import loki_snapshot as snapshot

ROOT = Path('/opt/repoguard/backups')
LABEL = 'com.repoguard.mysql-clone-owner'
PROFILE = snapshot.MYSQL_PROFILE
require = snapshot.require
AUTH = '''if [ -n "${MYSQL_ROOT_PASSWORD:-}" ]; then
  printf '%s' "$MYSQL_ROOT_PASSWORD"
elif [ -n "${MYSQL_ROOT_PASSWORD_FILE:-}" ] && [ -r "$MYSQL_ROOT_PASSWORD_FILE" ]; then
  password="$(cat "$MYSQL_ROOT_PASSWORD_FILE")"; printf '%s' "$password"
else exit 64; fi'''


def command(*args, data=None, timeout=45, limit=128 * 1024, combined=False):
    """Never include command output or credential material in public errors."""
    try:
        result = subprocess.run(args, input=data, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT if combined else subprocess.DEVNULL, timeout=timeout, check=False)
    except subprocess.TimeoutExpired:
        raise ValueError('command_outcome_unknown') from None
    require(result.returncode == 0, 'command_failed')
    require(len(result.stdout) <= limit, 'command_output_limit')
    return result.stdout


def docker(*args, **kwargs):
    # Ignore an operator's remote context: physical paths belong to this host.
    return command('docker', '--host', 'unix:///var/run/docker.sock', *args, **kwargs)


def inspect(container):
    # Do not retrieve Docker's environment, which can contain passwords.
    return json.loads(docker('inspect', '--format',
        '{"id":{{json .Id}},"image":{{json .Image}},"state":{{json .State}},'
        '"labels":{{json .Config.Labels}},"mounts":{{json .Mounts}},'
        '"network":{{json .HostConfig.NetworkMode}}}', container))


def query(container, password, sql, timeout=30):
    require(b'\n' not in password and b'\r' not in password and 0 < len(password) <= 4096,
        'unsupported_root_credential')
    return docker('exec', '-i', container, 'sh', '-c',
        'IFS= read -r MYSQL_PWD; export MYSQL_PWD; exec mysql --no-defaults '
        '--user=root --protocol=SOCKET --socket=/var/run/mysqld/mysqld.sock '
        '--batch --raw --skip-column-names --binary-mode --default-character-set=utf8mb4',
        data=password + b'\n' + sql.encode(), timeout=timeout, limit=2 * 1024 * 1024).decode().strip()


def write_record(path, value):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'w') as output:
        json.dump(value, output, sort_keys=True, indent=2)
        output.flush()
        os.fsync(output.fileno())


def private_directory(path):
    path = Path(path)
    require(path.is_absolute() and path.resolve() == path and path.is_relative_to(ROOT)
        and path != ROOT and path.parent.is_dir() and not path.exists(), 'unsafe_backup_directory')
    for parent in (path.parent, *path.parents):
        require(parent.is_dir() and not parent.is_symlink() and parent.stat().st_uid == 0
            and not parent.stat().st_mode & 0o022, 'unsafe_backup_parent')
    path.mkdir(mode=0o700)
    return path


def datafile_path(name):
    require(isinstance(name, str) and 0 < len(name) <= 4096 and not any(ord(c) < 32 for c in name)
        and '\\' not in name and '..' not in name.split('/'), 'external_tablespaces')
    path = PurePosixPath(name)
    root = PurePosixPath('/var/lib/mysql')
    if not path.is_absolute():
        path = root / path
    require(path != root and path.is_relative_to(root), 'external_tablespaces')
    return str(path)


def verify_datafiles(container, password):
    # MySQL reports both bare relative names (including undo files) and ./ paths.
    # Resolve inside the donor too: a lexically internal path may be a symlink.
    paths = query(container, password, 'SELECT PATH FROM information_schema.INNODB_DATAFILES ORDER BY PATH;').splitlines()
    require(0 < len(paths) <= PROFILE.max_entries, 'unsupported_datafile_set')
    names = [datafile_path(name) for name in paths]
    require(docker('exec', container, 'readlink', '-e', '/var/lib/mysql').strip()
        == b'/var/lib/mysql', 'external_tablespaces')
    canonical = docker('exec', '-i', container, 'sh', '-c',
        'while IFS= read -r path; do readlink -e -- "$path" || exit 64; done',
        data=('\n'.join(names) + '\n').encode(), limit=2 * 1024 * 1024).decode().splitlines()
    require(len(canonical) == len(names) and all(datafile_path(name) == name for name in canonical),
        'external_tablespaces')


def preflight(container, database, password):
    require(re.fullmatch('[A-Za-z0-9_]{1,64}', database), 'unsupported_database_name')
    version = query(container, password, 'SELECT @@version;')
    require(re.fullmatch(r'8\.0\.[0-9]+', version), 'unsupported_mysql_version')
    require(query(container, password, 'SELECT @@datadir;') == '/var/lib/mysql/', 'unsupported_datadir')
    checks = {
        'clone_plugin_not_active': "SELECT COUNT(*) FROM information_schema.PLUGINS WHERE PLUGIN_NAME='clone' AND PLUGIN_STATUS='ACTIVE';",
        'extra_user_databases': "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME NOT IN ('mysql','sys','information_schema','performance_schema','" + database + "');",
        'non_innodb_tables': "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA NOT IN ('mysql','sys','information_schema','performance_schema') AND TABLE_TYPE='BASE TABLE' AND ENGINE <> 'InnoDB';",
        'encrypted_tablespaces': "SELECT COUNT(*) FROM information_schema.INNODB_TABLESPACES WHERE ENCRYPTION='Y';",
        'clone_already_running': "SELECT COUNT(*) FROM performance_schema.clone_status WHERE STATE='In Progress';",
    }
    for reason, sql in checks.items():
        try:
            result = query(container, password, 'SET SESSION MAX_EXECUTION_TIME=3000;' + sql)
        except ValueError:
            raise ValueError(reason + '_query_failed') from None
        require(result == ('1' if reason == 'clone_plugin_not_active' else '0'), reason)
    require(query(container, password, "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='"
        + database + "';") == '1', 'database_not_found')
    verify_datafiles(container, password)
    return version


def clone_completed(container, password, destination):
    return query(container, password, "SELECT STATE,ERROR_NO FROM performance_schema.clone_status WHERE TRIM(TRAILING '/' FROM DESTINATION)='"
        + destination + "' ORDER BY ID DESC LIMIT 1;") == 'Completed\t0'


def table_checks(container, password, database):
    tables = query(container, password, "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='"
        + database + "' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME;").splitlines()
    require(0 < len(tables) <= 2000 and all(re.fullmatch('[A-Za-z0-9_]{1,64}', name) for name in tables),
        'unsupported_restored_table_set')
    rows = 0
    for name in tables:
        quoted = '`' + database + '`.`' + name + '`'
        result = query(container, password, 'CHECK TABLE ' + quoted + ';').splitlines()
        require(result == [database + '.' + name + '\tcheck\tstatus\tOK'], 'restored_table_check_failed')
        count = query(container, password, 'SELECT /*+ MAX_EXECUTION_TIME(3000) */ COUNT(*) FROM ' + quoted + ';')
        require(count.isdecimal(), 'invalid_restored_row_count')
        rows += int(count)
    return {'tables': len(tables), 'rows': rows}


def verify_recipient(name, owner, image, restored):
    value = inspect(name)
    mounts = value['mounts']
    require(value['labels'].get(LABEL) == owner and value['image'] == image and value['network'] == 'none'
        and len(mounts) == 1 and mounts[0]['Type'] == 'bind'
        and mounts[0]['Source'] == str(restored) and mounts[0]['Destination'] == '/var/lib/mysql',
        'recipient_identity_mismatch')
    return value


def restore_clone_root_metadata(container, clone_path, destination):
    # Copying clone/. copies its contents; the pre-created host root keeps its own metadata.
    raw = docker('exec', container, 'stat', '-c', '%u\t%g\t%a', clone_path).decode().strip().split('\t')
    require(len(raw) == 3 and all(re.fullmatch('[0-9]{1,5}', part) for part in raw[:2])
        and re.fullmatch('[0-7]{3,4}', raw[2]), 'invalid_clone_root_metadata')
    uid, gid, mode = int(raw[0]), int(raw[1]), int(raw[2], 8)
    mysql_uid = docker('exec', container, 'id', '-u', 'mysql').strip()
    require(uid <= 65535 and gid <= 65535 and mode <= 0o7777 and mysql_uid == str(uid).encode(),
        'clone_root_owner_mismatch')
    changed = destination.stat().st_uid != uid or destination.stat().st_gid != gid
    os.chown(destination, uid, gid)
    os.chmod(destination, mode & 0o777)
    return changed


def recipient_diagnostics(container):
    state = inspect(container)['state']
    raw = docker('logs', '--tail', '100', container, limit=65536, combined=True)
    return {'exitCode': int(state['ExitCode']), 'oomKilled': bool(state['OOMKilled']),
        'mysqlLogCodes': sorted({int(code) for code in re.findall(rb'\[MY-([0-9]{6})\]', raw)})[:24],
        'permissionDenied': b'Permission denied' in raw}


def run(container, database, directory, key_file):
    import fcntl  # The production operation is Linux/root-only.
    require(os.name == 'posix' and os.geteuid() == 0, 'linux_root_required')
    require(re.fullmatch('[A-Za-z0-9][A-Za-z0-9_.-]{0,100}', container), 'invalid_mysql_container')
    key_file = snapshot.protected_key(key_file)
    require(key_file.is_absolute() and key_file.resolve() == key_file and key_file.stat().st_nlink == 1
        and all(parent.stat().st_uid == 0 and not parent.stat().st_mode & 0o022 for parent in key_file.parents),
        'unsafe_backup_key_links_or_parents')
    os.umask(0o077)
    directory = private_directory(directory)
    owner = 'mysql-clone-' + uuid.uuid4().hex
    recipient = 'repoguard-' + owner
    donor_path = '/var/lib/mysql-files/' + owner
    clone_path = donor_path + '/clone'
    source = directory / 'source-data'
    restored = directory / 'restore-data'
    record = {'formatVersion': 1, 'scope': 'mysql-physical-clone', 'owner': owner,
        'restoreVerified': False, 'state': 'running', 'phase': 'preflight',
        'startedUtc': datetime.now(timezone.utc).isoformat()}
    clone_known_complete = False
    donor_directory_submitted = False
    donor_directory_removed = False
    recipient_id = None
    recipient_create_submitted = False
    donor_id = None
    lock = None
    try:
        lock_path = ROOT / '.mysql-clone.lock'
        descriptor = os.open(lock_path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        lock = os.fdopen(descriptor, 'a')
        require(os.fstat(descriptor).st_uid == 0 and stat.S_ISREG(os.fstat(descriptor).st_mode)
            and os.fstat(descriptor).st_nlink == 1 and not os.fstat(descriptor).st_mode & 0o077, 'unsafe_clone_lock')
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        before = inspect(container)
        donor_id = before['id']
        require(before['state']['Status'] == 'running' and not before['state']['OOMKilled'], 'donor_not_running')
        image = before['image']
        require(re.fullmatch('sha256:[a-f0-9]{64}', image), 'invalid_donor_image_id')
        password = docker('exec', donor_id, 'sh', '-c', AUTH, limit=4096)
        version = preflight(donor_id, database, password)
        record.update(mysqlVersion=version, imageId=image, donorContainerId=donor_id)
        # Reserve room for the clone, encrypted archive, staging copy and restored pages.
        data_kib = docker('exec', donor_id, 'du', '--apparent-size', '-sk', '/var/lib/mysql').split()[0]
        require(data_kib.isdigit(), 'invalid_donor_size')
        data_bytes = int(data_kib) * 1024
        require(0 < data_bytes <= PROFILE.max_data, 'donor_size_limit')
        require(shutil.disk_usage(directory).free >= 4 * data_bytes + 512 * 1024 ** 2, 'insufficient_clone_disk')
        donor_free = docker('exec', donor_id, 'df', '-Pk', '/var/lib/mysql-files').splitlines()[-1].split()[3]
        require(donor_free.isdigit() and int(donor_free) * 1024 >= data_bytes + 128 * 1024 ** 2,
            'insufficient_donor_clone_disk')
        available = Path('/proc/meminfo').read_text()
        memory = re.search(r'^MemAvailable:\s+(\d+) kB$', available, re.M)
        require(memory and int(memory[1]) >= 640 * 1024, 'insufficient_restore_memory')
        write_record(directory / 'intent.json', dict(record, donorCloneDirectory=donor_path,
            recipientName=recipient, sourceDirectory=str(source), restoreDirectory=str(restored)))
        donor_directory_submitted = True
        docker('exec', '--user', '0', donor_id, 'sh', '-c',
            'test -d /var/lib/mysql-files && test ! -L /var/lib/mysql-files && '
            'test ! -e "$1" && mkdir -m 700 -- "$1" && chown mysql:mysql -- "$1"', 'sh', donor_path)
        record['cloneOutcome'] = 'unknown'
        record['phase'] = 'clone'
        query(donor_id, password, "CLONE LOCAL DATA DIRECTORY='" + clone_path + "';", timeout=900)
        clone_known_complete = clone_completed(donor_id, password, clone_path)
        require(clone_known_complete, 'clone_completion_not_proven')
        record['cloneOutcome'] = 'completed'
        clone_kib = docker('exec', donor_id, 'du', '--apparent-size', '-sk', clone_path).split()[0]
        require(clone_kib.isdigit() and 0 < int(clone_kib) * 1024 <= PROFILE.max_data, 'clone_size_limit')
        require(shutil.disk_usage(directory).free >= 3 * int(clone_kib) * 1024 + 512 * 1024 ** 2,
            'insufficient_archive_disk')
        source.mkdir(mode=0o700)
        docker('cp', '--archive', donor_id + ':' + clone_path + '/.', str(source), timeout=180, limit=1024)
        record['stagingRootOwnershipCorrected'] = restore_clone_root_metadata(donor_id, clone_path, source)
        baseline = snapshot.catalog({'mysql': source}, PROFILE)
        require(any(item['name'] == 'mysql/mysql.ibd' for item in baseline['entries']), 'clone_system_tables_missing')
        def quiescent():
            return clone_completed(donor_id, password, clone_path) and snapshot.catalog({'mysql': source}, PROFILE) == baseline
        record['phase'] = 'encrypt'
        archive = snapshot.create_snapshot({'mysql': source}, directory / 'mysql.clone.tar.gz.enc', key_file,
            quiescent, PROFILE)
        record['archive'] = archive
        # Recovery always uses newly decrypted pages, not the unencrypted staging copy.
        restored.mkdir(mode=0o700)
        record['phase'] = 'restore'
        result = snapshot.restore_snapshot(archive, {'mysql': restored}, key_file, PROFILE)
        require(snapshot.catalog({'mysql': restored}, PROFILE) == baseline, 'restored_file_tree_mismatch')
        record['physicalFilesVerified'] = True
        record['phase'] = 'verify_mysql'
        recipient_create_submitted = True
        record['recipientName'] = recipient
        raw_id = docker('create', '--name', recipient, '--label', LABEL + '=' + owner,
            '--network', 'none', '--restart', 'no', '--memory', '384m', '--memory-swap', '384m',
            '--cpus', '0.50', '--pids-limit', '128', '--security-opt', 'no-new-privileges',
            '--user', 'mysql', '--mount', 'type=bind,src=' + str(restored) + ',dst=/var/lib/mysql',
            '--entrypoint', 'mysqld', image, '--datadir=/var/lib/mysql',
            '--socket=/var/run/mysqld/mysqld.sock', '--skip-networking', '--mysqlx=OFF',
            '--skip-log-bin', '--skip-replica-start', '--event-scheduler=OFF',
            '--innodb-buffer-pool-size=64M', '--performance-schema=OFF', '--max-connections=8').decode().strip()
        require(re.fullmatch('[a-f0-9]{64}', raw_id), 'invalid_recipient_id')
        recipient_id = raw_id
        record['recipientContainerId'] = recipient_id
        verify_recipient(recipient_id, owner, image, restored)
        docker('start', recipient_id)
        deadline = time.monotonic() + 120
        while True:
            value = verify_recipient(recipient_id, owner, image, restored)
            require(value['state']['Status'] == 'running' and not value['state']['OOMKilled'], 'recipient_exited')
            try:
                pid_one = docker('exec', recipient_id, 'cat', '/proc/1/comm').strip()
                if pid_one == b'mysqld' and query(recipient_id, password, 'SELECT @@version;') == version:
                    break
            except ValueError:
                pass
            require(time.monotonic() < deadline, 'recipient_readiness_timeout')
            time.sleep(1)
        counts = table_checks(recipient_id, password, database)
        current = inspect(donor_id)
        require(current['id'] == donor_id and current['image'] == image
            and current['state']['StartedAt'] == before['state']['StartedAt']
            and current['state']['Status'] == 'running' and not current['state']['OOMKilled'], 'donor_runtime_changed')
        record.update(state='verified', restoreVerified=True, restored=counts,
            restoredFiles=result['restoredFiles'], donorRuntimeUnchanged=True, phase='complete')
    except Exception as error:
        record['state'] = 'failed'
        record['restoreVerified'] = False
        reason = str(error) if isinstance(error, ValueError) else type(error).__name__
        record['reason'] = reason if re.fullmatch('[A-Za-z_]{1,100}', reason) else 'backup_operation_failed'
        if recipient_id:
            try:
                record['recipientDiagnostics'] = recipient_diagnostics(recipient_id)
            except Exception:
                record['recipientDiagnosticsUnavailable'] = True
    finally:
        cleanup_ok = True
        try:
            if recipient_id:
                verify_recipient(recipient_id, owner, record['imageId'], restored)
                docker('rm', '--force', recipient_id)
                require(not docker('ps', '--all', '--quiet', '--no-trunc', '--filter', 'id=' + recipient_id).strip(),
                    'recipient_cleanup_not_proven')
            elif recipient_create_submitted:
                raise ValueError('recipient_creation_unknown')
            # Never remove a directory while the server-side CLONE outcome is unknown.
            if clone_known_complete:
                docker('exec', '--user', '0', donor_id, 'sh', '-c',
                    'test ! -L "$1" && test -d "$1" && rm -rf -- "$1" && test ! -e "$1"', 'sh', donor_path)
                donor_directory_removed = True
            for path in (source, restored):
                if path.exists():
                    require(path.parent == directory and not path.is_symlink(), 'unsafe_owned_cleanup')
                    shutil.rmtree(path)
        except Exception:
            cleanup_ok = False
            record.update(state='failed', restoreVerified=False, cleanupReason='owned_cleanup_failed')
        if donor_directory_submitted and not clone_known_complete:
            cleanup_ok = False
        record.update(ownedResourcesCleaned=cleanup_ok,
            donorCloneRetained=donor_directory_submitted and not donor_directory_removed,
            recipientCreationUnknown=recipient_create_submitted and not recipient_id,
            finishedUtc=datetime.now(timezone.utc).isoformat())
        write_record(directory / 'receipt.json', record)
        if lock:
            lock.close()
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysql-container', required=True)
    parser.add_argument('--database', required=True)
    parser.add_argument('--directory', required=True, type=Path)
    parser.add_argument('--key-file', required=True, type=Path)
    args = parser.parse_args()
    try:
        result = run(args.mysql_container, args.database, args.directory, args.key_file)
        # Only aggregate status leaves the protected backup directory.
        print(json.dumps({key: result[key] for key in ('state', 'restoreVerified', 'ownedResourcesCleaned')}))
        return 0 if result['restoreVerified'] and result['ownedResourcesCleaned'] else 1
    except Exception:
        print('{"state":"failed","restoreVerified":false,"reason":"backup_precondition_failed"}')
        return 1


if __name__ == '__main__':
    sys.exit(main())
