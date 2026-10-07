"""Bounded regular-file reads for local review; refuse links and verify the opened handle stays in the repo."""
import os
from pathlib import Path
import stat


class ContentError(Exception):
    pass


def read_worktree(repo, name, limit):
    repo = repo.resolve()
    target = repo / name
    cursor = repo
    for part in Path(name).parts:
        cursor = cursor / part
        info = cursor.lstat()
        if stat.S_ISLNK(info.st_mode) or getattr(info, 'st_file_attributes', 0) & 0x400:
            raise ContentError('linked_path_not_scanned')
    if not stat.S_ISREG(info.st_mode):
        raise ContentError('non_regular_file_not_scanned')
    descriptor = os.open(target, os.O_RDONLY | getattr(os, 'O_BINARY', 0) | getattr(os, 'O_NOFOLLOW', 0))
    with os.fdopen(descriptor, 'rb') as stream:
        if os.name == 'nt':
            import ctypes
            from ctypes import wintypes
            import msvcrt
            function = ctypes.WinDLL('kernel32', use_last_error=True).GetFinalPathNameByHandleW
            function.argtypes = [wintypes.HANDLE, wintypes.LPWSTR, wintypes.DWORD, wintypes.DWORD]
            function.restype = wintypes.DWORD
            buffer = ctypes.create_unicode_buffer(32768)
            count = function(msvcrt.get_osfhandle(descriptor), buffer, len(buffer), 0)
            if not count or count >= len(buffer):
                raise ContentError('cannot_verify_file_handle')
            resolved = buffer.value
            if resolved.startswith('\\\\?\\UNC\\'):
                resolved = '\\\\' + resolved[8:]
            elif resolved.startswith('\\\\?\\'):
                resolved = resolved[4:]
            opened = Path(resolved)
        elif Path('/proc/self/fd').is_dir():
            opened = Path(os.readlink('/proc/self/fd/' + str(descriptor)))
        else:
            raise ContentError('file_handle_verification_unavailable')
        try:
            opened.relative_to(repo)
        except ValueError:
            raise ContentError('file_handle_outside_repository') from None
        before = os.fstat(descriptor)
        if not stat.S_ISREG(before.st_mode):
            raise ContentError('non_regular_file_not_scanned')
        if before.st_size > limit:
            raise ContentError('file_content_budget_exceeded')
        content = stream.read(limit + 1)
        after = os.fstat(descriptor)
        if len(content) > limit:
            raise ContentError('file_content_budget_exceeded')
        if (before.st_size, before.st_mtime_ns, before.st_ino) != (after.st_size, after.st_mtime_ns, after.st_ino):
            raise ContentError('file_changed_during_read')
        return content
