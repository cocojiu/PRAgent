"""Encrypted, bounded snapshots for quiescent Loki and Alloy data volumes."""
import gzip
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import tarfile

MAX_DATA = 256 * 1024 * 1024
MAX_ARCHIVE = 512 * 1024 * 1024
MAX_ENTRIES = 20_000
ROLES = {'loki', 'alloy'}


def require(value, reason):
    if not value:
        raise ValueError(reason)


def digest(path):
    result = hashlib.sha256()
    with Path(path).open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def safe_name(name):
    require(isinstance(name, str) and 0 < len(name) <= 4096 and '\\' not in name and '\x00' not in name,
        'unsafe_snapshot_name')
    parts = name.split('/')
    require(parts[0] in ROLES and all(part not in ('', '.', '..') for part in parts), 'unsafe_snapshot_name')
    require(not PurePosixPath(name).is_absolute(), 'unsafe_snapshot_name')
    return parts


def protected_key(path):
    path = Path(path)
    require(os.name == 'posix' and path.is_file() and not path.is_symlink()
        and path.stat().st_uid == 0 and stat.S_IMODE(path.stat().st_mode) == 0o600
        and 32 <= path.stat().st_size <= 4096, 'protected_backup_key_unavailable')
    return path


def catalog(volumes):
    require(set(volumes) == ROLES, 'unexpected_snapshot_volume_set')
    entries = []
    total = 0
    for role in sorted(volumes):
        root = Path(volumes[role])
        require(root.is_dir() and not root.is_symlink() and root.resolve() == root, 'unsafe_snapshot_root')
        def walk(directory):
            yield directory
            children = []
            with os.scandir(directory) as scanned:
                for child in scanned:
                    require(len(children) < MAX_ENTRIES, 'snapshot_entry_limit_or_symlink')
                    children.append(Path(child.path))
            for child in sorted(children):
                require(not child.is_symlink(), 'snapshot_entry_limit_or_symlink')
                if child.is_dir():
                    yield from walk(child)
                else:
                    yield child

        for path in walk(root):
            require(len(entries) < MAX_ENTRIES and not path.is_symlink(), 'snapshot_entry_limit_or_symlink')
            metadata = path.lstat()
            require(stat.S_ISDIR(metadata.st_mode) or stat.S_ISREG(metadata.st_mode), 'snapshot_special_file')
            name = role if path == root else role + '/' + path.relative_to(root).as_posix()
            safe_name(name)
            item = {'name': name, 'directory': path.is_dir(), 'mode': stat.S_IMODE(metadata.st_mode),
                'uid': metadata.st_uid, 'gid': metadata.st_gid, 'mtime': metadata.st_mtime,
                'bytes': 0 if path.is_dir() else metadata.st_size}
            total += item['bytes']
            require(total <= MAX_DATA, 'snapshot_data_budget_exceeded')
            if not item['directory']:
                item['sha256'] = digest(path)
            entries.append(item)
    return {'formatVersion': 1, 'totalBytes': total, 'entries': entries}


def create_snapshot(volumes, destination, key_file, quiescent):
    """The caller must prove that every writer is stopped, before and after reading."""
    key_file = protected_key(key_file)
    require(quiescent(), 'snapshot_writers_still_running')
    metadata = catalog(volumes)
    destination = Path(destination)
    require(destination.parent.resolve() == destination.parent and destination.parent.is_dir()
        and not destination.exists() and not destination.is_symlink(), 'unsafe_snapshot_destination')
    partial = destination.with_name(destination.name + '.partial')
    descriptor = os.open(partial, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'wb') as output:
        process = subprocess.Popen(['openssl', 'enc', '-aes-256-cbc', '-pbkdf2', '-iter', '250000', '-salt',
            '-pass', 'file:' + str(key_file)], stdin=subprocess.PIPE, stdout=output, stderr=subprocess.PIPE)
        try:
            with gzip.GzipFile(fileobj=process.stdin, mode='wb', mtime=0) as compressed:
                with tarfile.open(fileobj=compressed, mode='w|') as archive:
                    raw = json.dumps(metadata, sort_keys=True, separators=(',', ':')).encode()
                    require(len(raw) <= 4 * 1024 * 1024, 'snapshot_catalog_too_large')
                    info = tarfile.TarInfo('catalog.json')
                    info.size = len(raw)
                    info.mode = 0o600
                    archive.addfile(info, io.BytesIO(raw))
                    for item in metadata['entries']:
                        parts = safe_name(item['name'])
                        path = Path(volumes[parts[0]]).joinpath(*parts[1:])
                        require(not path.is_symlink(), 'snapshot_path_changed')
                        info = tarfile.TarInfo(item['name'])
                        info.uid, info.gid, info.mode, info.mtime = item['uid'], item['gid'], item['mode'], item['mtime']
                        info.type = tarfile.DIRTYPE if item['directory'] else tarfile.REGTYPE
                        info.size = item['bytes']
                        if item['directory']:
                            archive.addfile(info)
                        else:
                            with path.open('rb') as source:
                                archive.addfile(info, source)
            process.stdin.close()
            process.wait(timeout=30)
            require(process.returncode == 0, 'snapshot_encryption_failed')
            output.flush()
            os.fsync(output.fileno())
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=10)
            process.stderr.close()
    require(quiescent(), 'snapshot_writer_state_changed')
    require(0 < partial.stat().st_size <= MAX_ARCHIVE, 'snapshot_archive_budget_exceeded')
    # Never overwrite another archive, including one created during encryption.
    os.link(partial, destination)
    partial.unlink()
    return {'state': 'encrypted', 'archive': str(destination), 'archiveSha256': digest(destination),
        'archiveBytes': destination.stat().st_size, 'sourceBytes': metadata['totalBytes'],
        'entryCount': len(metadata['entries']), 'restoreVerified': False}


class BoundedReader:
    def __init__(self, source):
        self.source = source
        self.count = 0

    def read(self, size=-1):
        require(0 <= size <= 16 * 1024 * 1024, 'unbounded_snapshot_read')
        raw = self.source.read(size)
        self.count += len(raw)
        require(self.count <= MAX_ARCHIVE, 'snapshot_expansion_budget_exceeded')
        return raw


def restore_snapshot(snapshot, destinations, key_file):
    """Restore only into new empty roots; verify every file before reporting success."""
    key_file = protected_key(key_file)
    archive_path = Path(snapshot['archive'])
    require(archive_path.is_file() and not archive_path.is_symlink()
        and 0 < archive_path.stat().st_size <= MAX_ARCHIVE, 'unsafe_snapshot_archive')
    require(isinstance(snapshot.get('archiveSha256'), str)
        and re.fullmatch('[a-f0-9]{64}', snapshot['archiveSha256'])
        and digest(archive_path) == snapshot['archiveSha256'], 'snapshot_checksum_mismatch')
    require(set(destinations) == ROLES, 'unexpected_restore_volume_set')
    roots = {role: Path(path) for role, path in destinations.items()}
    for root in roots.values():
        require(root.is_dir() and not root.is_symlink() and root.resolve() == root
            and not any(root.iterdir()), 'restore_destination_not_empty_or_unsafe')
    require(len({str(root) for root in roots.values()}) == 2
        and not any(first.is_relative_to(second) for first in roots.values() for second in roots.values() if first != second),
        'restore_roots_overlap')
    process = subprocess.Popen(['openssl', 'enc', '-d', '-aes-256-cbc', '-pbkdf2', '-iter', '250000',
        '-pass', 'file:' + str(key_file), '-in', str(archive_path)], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    restored = 0
    directories = []
    try:
        with gzip.GzipFile(fileobj=process.stdout, mode='rb') as compressed:
            stream = BoundedReader(compressed)
            with tarfile.open(fileobj=stream, mode='r|') as archive:
                first = archive.next()
                require(first is not None and first.name == 'catalog.json' and first.isfile()
                    and 0 < first.size <= 4 * 1024 * 1024, 'snapshot_catalog_missing')
                metadata = json.load(archive.extractfile(first))
                entries = metadata.get('entries')
                require(type(metadata.get('formatVersion')) is int and metadata['formatVersion'] == 1 and isinstance(entries, list)
                    and 2 <= len(entries) <= MAX_ENTRIES, 'invalid_snapshot_catalog')
                require(type(metadata.get('totalBytes')) is int and 0 <= metadata['totalBytes'] <= MAX_DATA
                    and all(any(item.get('name') == role and item.get('directory') is True and item.get('bytes') == 0
                        for item in entries) for role in ROLES), 'snapshot_root_metadata_missing')
                names = set()
                size = 0
                for item in entries:
                    parts = safe_name(item['name'])
                    require(item['name'] not in names and type(item['bytes']) is int and item['bytes'] >= 0,
                        'duplicate_or_invalid_snapshot_entry')
                    require(type(item.get('directory')) is bool
                        and all(type(item.get(key)) is int and 0 <= item[key] <= 65535 for key in ('uid', 'gid'))
                        and type(item.get('mode')) is int and 0 <= item['mode'] <= 0o7777
                        and type(item.get('mtime')) in (int, float) and 0 <= item['mtime'] <= 2 ** 40,
                        'invalid_snapshot_metadata')
                    names.add(item['name'])
                    size += item['bytes']
                    require(size <= MAX_DATA, 'snapshot_data_budget_exceeded')
                    member = archive.next()
                    require(member is not None and member.name == item['name'] and not member.issym()
                        and not member.islnk(), 'snapshot_member_mismatch')
                    target = roots[parts[0]].joinpath(*parts[1:])
                    require(target.is_relative_to(roots[parts[0]]) and target.parent.resolve() == target.parent,
                        'restore_path_escaped')
                    if item['directory']:
                        require(member.isdir() and member.size == item['bytes'] == 0, 'snapshot_directory_mismatch')
                        if target != roots[parts[0]]:
                            target.mkdir(mode=0o700)
                        directories.append((target, item))
                    else:
                        require(member.isfile() and member.size == item['bytes'] and target.parent.is_dir(), 'snapshot_file_mismatch')
                        file_descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
                        calculated = hashlib.sha256()
                        with os.fdopen(file_descriptor, 'wb') as output:
                            source = archive.extractfile(member)
                            for chunk in iter(lambda: source.read(1024 * 1024), b''):
                                calculated.update(chunk)
                                output.write(chunk)
                        require(calculated.hexdigest() == item.get('sha256'), 'restored_file_checksum_mismatch')
                        os.chown(target, item['uid'], item['gid'])
                        os.chmod(target, item['mode'] & 0o777)
                        os.utime(target, (item['mtime'], item['mtime']))
                        restored += 1
                require(size == metadata['totalBytes'] and archive.next() is None, 'unexpected_snapshot_members')
            # Check the gzip trailer and encryption padding, not just tar headers.
            while stream.read(1024 * 1024):
                pass
        process.stdout.close()
        process.wait(timeout=30)
        require(process.returncode == 0, 'snapshot_decryption_failed')
        for target, item in reversed(directories):
            os.chown(target, item['uid'], item['gid'])
            os.chmod(target, item['mode'] & 0o777)
            os.utime(target, (item['mtime'], item['mtime']))
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=10)
        process.stderr.close()
    return {'state': 'verified', 'restoreVerified': True, 'restoredFiles': restored,
        'sourceBytes': size, 'archiveSha256': snapshot['archiveSha256']}
