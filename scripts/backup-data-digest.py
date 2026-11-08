#!/usr/bin/env python3
"""Fingerprint data in the pinned single-row mysqldump format, without storing SQL."""
import argparse
from contextlib import ExitStack
import hashlib
import heapq
import hmac
import os
from pathlib import Path
import re
import sys
import tempfile

ALGORITHM = 'hmac-sha256-sorted-rows-v1'
MAX_LINE = 64 * 1024 * 1024
CHUNK_ROWS = 8192
MERGE_FAN_IN = 16
NAME = rb'[A-Za-z0-9_]+'
LOCK = re.compile(rb'LOCK TABLES `(' + NAME + rb')` WRITE;')
INSERT = re.compile(rb'INSERT INTO `(' + NAME + rb')` \((?:`(?:[^`]|``)+`(?:, )?)*\) VALUES (\(.*\));')
KEYS = re.compile(rb'/\*!40000 ALTER TABLE `(' + NAME + rb')` (?:DISABLE|ENABLE) KEYS \*/;')


def require(condition):
    if not condition:
        raise ValueError('unsupported_or_incomplete_dump')


class SqlBoundary:
    """Track SQL strings/comments so routine bodies cannot impersonate data sections."""
    def __init__(self):
        self.quote = None
        self.comment = False
        self.pending = False

    @property
    def ready(self):
        return self.quote is None and not self.comment and not self.pending

    def feed(self, line):
        i = 0
        while i < len(line):
            c = line[i:i + 1]
            pair = line[i:i + 2]
            if self.comment:
                if pair == b'*/':
                    self.comment = False
                    i += 2
                    continue
            elif self.quote:
                if c == b'\\':
                    i += 2
                    continue
                if c == self.quote:
                    if pair == c + c:
                        i += 2
                        continue
                    self.quote = None
            elif pair == b'/*':
                self.comment = True
                i += 2
                continue
            elif c == b'#' or (pair == b'--' and (i + 2 == len(line) or line[i + 2] <= 32)):
                break
            elif c in (b"'", b'"', b'`'):
                self.quote = c
                self.pending = True
            elif c == b';':
                self.pending = False
            elif not c.isspace():
                self.pending = True
            i += 1


def single_row(values):
    """Dump literals may contain SQL-looking text, but never a second value tuple."""
    quote = None
    depth = 0
    i = 0
    while i < len(values):
        c = values[i:i + 1]
        if quote:
            if c == b'\\':
                i += 2
                continue
            if c == quote:
                if values[i:i + 2] == quote + quote:
                    i += 2
                    continue
                quote = None
        elif c in (b"'", b'"'):
            quote = c
        elif c == b'(':
            depth += 1
            require(depth == 1 and i == 0)
        elif c == b')':
            depth -= 1
            require(depth == 0 and i == len(values) - 1)
        elif c in (b';', b'`') or values[i:i + 2] in (b'/*', b'--') or c == b'#':
            require(False)
        i += 1
    require(quote is None and depth == 0 and values.startswith(b'(') and values.endswith(b')'))


class SortedDigests:
    """External merge of keyed fixed-size hashes; duplicates retain their multiplicity."""
    def __init__(self, directory):
        self.directory = directory
        self.buffer = []
        self.parts = []
        self.serial = 0

    def new_path(self):
        self.serial += 1
        return self.directory / str(self.serial)

    def add(self, value):
        self.buffer.append(value)
        if len(self.buffer) == CHUNK_ROWS:
            self.flush()

    def flush(self):
        if self.buffer:
            path = self.new_path()
            with path.open('xb') as stream:
                stream.writelines(value.hex().encode('ascii') + b'\n' for value in sorted(self.buffer))
            self.parts.append(path)
            self.buffer.clear()

    def finish(self):
        self.flush()
        while len(self.parts) > 1:
            merged = []
            for offset in range(0, len(self.parts), MERGE_FAN_IN):
                group = self.parts[offset:offset + MERGE_FAN_IN]
                path = self.new_path()
                with ExitStack() as stack, path.open('xb') as target:
                    streams = [stack.enter_context(part.open('rb')) for part in group]
                    target.writelines(heapq.merge(*streams))
                for part in group:
                    part.unlink()
                merged.append(path)
            self.parts = merged
        digest = hashlib.sha256()
        if self.parts:
            with self.parts[0].open('rb') as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b''):
                    digest.update(block)
        return digest.hexdigest()


def table_names(manifest):
    require(manifest.stat().st_size <= 1024 * 1024)
    lines = manifest.read_bytes().splitlines()
    if lines == [b'__EMPTY__\t0']:
        return set()
    names = set()
    for line in lines:
        fields = line.split(b'\t')
        require(len(fields) == 2 and re.fullmatch(NAME, fields[0]) and fields[1].isdigit())
        require(fields[0] not in names)
        names.add(fields[0])
    require(names)
    return names


def fingerprint(stream, expected, key, directory):
    raw = hashlib.sha256()
    sorted_rows = SortedDigests(directory)
    boundary = SqlBoundary()
    delimiter = b';'
    active = None
    seen = set()
    rows = 0
    while True:
        line = stream.readline(MAX_LINE + 1)
        if not line:
            break
        require(len(line) <= MAX_LINE and line.endswith(b'\n'))
        raw.update(line)
        text = line.rstrip(b'\r\n')
        if active is not None:
            insert = INSERT.fullmatch(text)
            if insert:
                require(insert[1] == active)
                single_row(insert[2])
                sorted_rows.add(hmac.digest(key, b'R\0' + text, 'sha256'))
                rows += 1
            elif text == b'UNLOCK TABLES;':
                active = None
            elif KEYS.fullmatch(text):
                require(KEYS.fullmatch(text)[1] == active)
            else:
                require(not text.strip())
            continue
        if boundary.ready and text.startswith(b'DELIMITER '):
            require(text in (b'DELIMITER ;', b'DELIMITER ;;'))
            delimiter = text[len(b'DELIMITER '):]
            continue
        lock = LOCK.fullmatch(text) if boundary.ready and delimiter == b';' else None
        if lock:
            active = lock[1]
            require(active in expected and active not in seen)
            seen.add(active)
            sorted_rows.add(hmac.digest(key, b'T\0' + active, 'sha256'))
            continue
        if boundary.ready and delimiter == b';':
            require(not text.startswith((b'INSERT ', b'REPLACE ', b'LOCK TABLES ', b'UNLOCK TABLES')))
        boundary.feed(line)
    require(active is None and boundary.ready and delimiter == b';' and seen == expected)
    return raw.hexdigest(), sorted_rows.finish(), rows, len(seen)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--temporary-directory', type=Path, required=True)
    args = parser.parse_args()
    os.umask(0o077)
    try:
        key = os.environ['BACKUP_ENCRYPTION_PASSWORD'].encode('utf-8')
        require(len(key) >= 32)
        expected = table_names(args.manifest)
        with tempfile.TemporaryDirectory(prefix='data-digest-', dir=args.temporary_directory) as directory:
            dump, data, rows, tables = fingerprint(sys.stdin.buffer, expected, key, Path(directory))
        print(f'{ALGORITHM}\t{dump}\t{data}\t{rows}\t{tables}')
    except (OSError, ValueError, KeyError):
        print('ERROR=backup_data_digest_failed', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
