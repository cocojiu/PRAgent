#!/usr/bin/env python3
"""Offline Git diff review using RepoGuard's Java detectors; no API, model, or GitHub writes."""
import argparse
import difflib
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import threading
from urllib.parse import quote
from local_codeowners import recommend
from local_review_content import ContentError, read_worktree

LIMIT = 2 * 1024 * 1024


class PreflightError(Exception):
    pass


def command(args, cwd, limit=LIMIT, data=None, timeout=30):
    env = dict(os.environ, GIT_OPTIONAL_LOCKS='0', GIT_NO_LAZY_FETCH='1', GIT_TERMINAL_PROMPT='0')
    process = subprocess.Popen(args, cwd=cwd, stdin=subprocess.PIPE if data is not None else subprocess.DEVNULL,
                               stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=env)
    chunks, oversized = [], threading.Event()
    def drain():
        size = 0
        while True:
            block = process.stdout.read(65536)
            if not block:
                break
            size += len(block)
            if size > limit:
                oversized.set()
                process.kill()
                break
            chunks.append(block)
    def feed():
        try:
            process.stdin.write(data)
            process.stdin.close()
        except (BrokenPipeError, OSError):
            pass
    reader = threading.Thread(target=drain, daemon=True)
    reader.start()
    if data is not None:
        threading.Thread(target=feed, daemon=True).start()
    try:
        code = process.wait(timeout=timeout)
        reader.join(timeout=2)
        if reader.is_alive() or oversized.is_set():
            raise PreflightError('command_output_budget_exceeded')
        if code:
            raise PreflightError('local_command_failed')
        return b''.join(chunks)
    except subprocess.TimeoutExpired:
        raise PreflightError('local_command_timeout') from None
    finally:
        if process.poll() is None:
            process.kill()
        process.wait()
        reader.join(timeout=2)
        process.stdout.close()


def git(repo, *args, limit=LIMIT):
    return command(['git', '-c', 'core.fsmonitor=false', '-c', 'protocol.allow=never', '--literal-pathspecs', *args], repo, limit)


def safe_path(path):
    if (not path or len(path) > 1024 or path.startswith('/') or '\\' in path
            or any(part in ('..', '') for part in path.split('/')) or ':' in path
            or len(path.split('/')) > 64 or any(ord(c) < 32 for c in path)):
        raise PreflightError('unsupported_git_path')
    return path


def collect(repo, base, staged, include_untracked=False, full_context=False):
    try:
        base_sha = git(repo, 'rev-parse', '--verify', '--end-of-options', base + '^{commit}').decode().strip()
    except PreflightError:
        raise PreflightError('base_revision_unavailable') from None
    if not re.fullmatch(r'[a-f0-9]{40}|[a-f0-9]{64}', base_sha):
        raise PreflightError('invalid_base_revision')
    head = git(repo, 'rev-parse', '--verify', 'HEAD').decode().strip()
    try:
        ancestor = git(repo, 'merge-base', base_sha, head).decode().strip()
    except PreflightError:
        raise PreflightError('merge_base_unavailable') from None
    options = ['--cached'] if staged else []
    diff_args = ['diff', '--no-ext-diff', '--no-textconv', '--find-renames=50%', '-l200']
    listing = git(repo, *diff_args, '--name-status', '-z', *options, ancestor, '--')
    fields = listing.decode('utf-8', 'strict').rstrip('\0').split('\0') if listing else []
    entries, index = [], 0
    while index < len(fields):
        status = fields[index]
        width = 3 if status.startswith('R') else 2
        if index + width > len(fields) or not status or (status[0] not in 'AMDRT'):
            raise PreflightError('unmerged_or_unsupported_git_status')
        old_path = safe_path(fields[index + 1]) if width == 3 else None
        path = safe_path(fields[index + width - 1])
        entries.append((status, path, old_path))
        index += width
    untracked = git(repo, 'ls-files', '--others', '--exclude-standard', '-z') if include_untracked else b''
    for path in untracked.decode('utf-8', 'strict').split('\0'):
        if path:
            entries.append(('UNTRACKED', safe_path(path), None))
    if len(entries) > 200:
        raise PreflightError('changed_file_budget_exceeded')
    files, gaps, size, snapshots, patches = [], [], 0, {}, {}

    def content_at(path):
        if staged:
            mode = git(repo, 'ls-files', '--stage', '-z', '--', path, limit=4096)
            if not mode.startswith((b'100644 ', b'100755 ')) or mode.count(b'\0') != 1:
                raise ContentError('non_regular_index_file_not_scanned')
            return git(repo, 'show', ':' + path, limit=256 * 1024)
        return read_worktree(repo, path, 256 * 1024)

    for status, path, old_path in entries:
        content, unavailable = None, None
        if (full_context or status == 'UNTRACKED') and status != 'D':
            try:
                raw = content_at(path)
                if b'\0' in raw:
                    unavailable = 'binary_content_not_scanned'
                else:
                    content = raw.decode('utf-8', 'strict')
                snapshots[path] = hashlib.sha256(raw).hexdigest()
            except UnicodeError:
                unavailable = 'non_utf8_content_not_scanned'
            except ContentError as error:
                if str(error) in ('file_content_budget_exceeded', 'file_changed_during_read'):
                    raise
                unavailable = str(error)
            except FileNotFoundError:
                raise PreflightError('diff_changed_during_collection') from None
        if status == 'UNTRACKED':
            patch = ''.join(difflib.unified_diff([], content.splitlines(keepends=True),
                            fromfile='/dev/null', tofile='b/' + path)) if content is not None else ''
        else:
            paths = [old_path, path] if old_path else [path]
            patch = git(repo, *diff_args, '--unified=3', *options,
                        ancestor, '--', *paths, limit=256 * 1024).decode('utf-8', 'strict')
            patches[path] = (paths, patch)
        if len(patch.encode('utf-8')) > 256 * 1024:
            raise PreflightError('file_content_budget_exceeded')
        size += len(patch.encode('utf-8'))
        if full_context and content is not None:
            size += len(content.encode('utf-8'))
        if size > LIMIT:
            raise PreflightError('total_diff_budget_exceeded')
        gap = unavailable
        if ' 160000\n' in patch or 'Subproject commit ' in patch:
            gap = 'submodule_not_scanned'
            patch = ''
        elif re.search(r'(?m)^(?:index .* 120000|new file mode 120000|deleted file mode 120000)$', patch):
            gap = 'symlink_not_scanned'
            patch = ''
        elif re.search(r'(?m)^Binary files ', patch):
            gap = 'binary_not_scanned'
        elif '\n@@ ' not in patch and status != 'D' and status != 'UNTRACKED' and not status.startswith('R'):
            gap = gap or 'metadata_only'
        if gap:
            gaps.append({'path': path, 'reason': gap})
        item = {'filename': path, 'status': 'renamed' if old_path else {'A': 'added', 'UNTRACKED': 'added', 'D': 'removed'}.get(status, 'modified'), 'patch': patch}
        if old_path:
            item['previousFilename'] = old_path
        if full_context:
            item['context'] = {'status': 'DELETED' if status == 'D' else 'AVAILABLE' if content is not None and not gap else 'UNAVAILABLE',
                               'content': content if content is not None and not gap else '',
                               'reason': gap or ('local_index_snapshot' if staged else 'local_worktree_snapshot')}
        if path in snapshots:
            item['contentSha256'] = snapshots[path]
        files.append(item)
    for paths, original in patches.values():
        if git(repo, *diff_args, '--unified=3', *options, ancestor, '--', *paths, limit=256 * 1024).decode('utf-8', 'strict') != original:
            raise PreflightError('diff_changed_during_collection')
    for path, expected in snapshots.items():
        if hashlib.sha256(content_at(path)).hexdigest() != expected:
            raise PreflightError('diff_changed_during_collection')
    if git(repo, *diff_args, '--name-status', '-z', *options, ancestor, '--') != listing:
        raise PreflightError('changed_file_list_changed_during_collection')
    if include_untracked and git(repo, 'ls-files', '--others', '--exclude-standard', '-z') != untracked:
        raise PreflightError('changed_file_list_changed_during_collection')
    if git(repo, 'rev-parse', '--verify', 'HEAD').decode().strip() != head:
        raise PreflightError('head_changed_during_collection')
    return base_sha, ancestor, head, files, gaps


def owners(repo, base_sha, files):
    paths = list(dict.fromkeys(path for file in files for path in (file.get('previousFilename'), file['filename']) if path))
    for path in ('.github/CODEOWNERS', 'CODEOWNERS', 'docs/CODEOWNERS'):
        entry = git(repo, 'ls-tree', '-z', base_sha, '--', path, limit=4096)
        if not entry:
            continue
        if not entry.startswith(b'100644 blob ') and not entry.startswith(b'100755 blob '):
            return {'status': 'UNSUPPORTED_FILE_MODE', 'source': path, 'recommendations': [], 'uncovered': paths}
        try:
            content = git(repo, 'show', base_sha + ':' + path, limit=128 * 1024).decode('utf-8', 'strict')
            return recommend(content, paths, {'commit': base_sha, 'path': path})
        except (PreflightError, UnicodeError):
            return {'status': 'UNREADABLE_OR_OVERSIZED_CODEOWNERS', 'source': path,
                    'recommendations': [], 'uncovered': paths}
    return {'status': 'NO_CODEOWNERS', 'recommendations': [], 'uncovered': paths}


def sarif(report):
    results = []
    for finding in report['findings']:
        location = {'artifactLocation': {'uri': quote(finding['filePath'], safe='/')}}
        if finding.get('lineNumber') and finding['lineNumber'] > 0:
            location['region'] = {'startLine': finding['lineNumber']}
        level = 'error' if finding['severity'] in ('HIGH', 'CRITICAL') else 'warning' if finding['severity'] == 'MEDIUM' else 'note'
        results.append({'ruleId': finding['ruleId'], 'level': level,
                        'message': {'text': finding['message']}, 'locations': [{'physicalLocation': location}]})
    return {'version': '2.1.0', '$schema': 'https://json.schemastore.org/sarif-2.1.0.json', 'runs': [{
        'tool': {'driver': {'name': 'RepoGuard Local Preflight', 'semanticVersion': '0.1.0'}},
        'invocations': [{'executionSuccessful': report['scanComplete']}],
        'properties': {k: report[k] for k in ('scope', 'policySource', 'productionPolicyVerified', 'gaps', 'codeowners',
                                             'untrackedFilesIncluded', 'fullFileContextRequested', 'fullFileContextLoaded', 'files')},
        'results': results}]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, default=Path.cwd())
    parser.add_argument('--base', default='origin/main')
    parser.add_argument('--staged', action='store_true', help='Compare merge base to index; otherwise compare tracked working tree')
    parser.add_argument('--include-untracked', action='store_true', help='Also review non-ignored untracked regular files (working-tree mode only)')
    parser.add_argument('--full-context', action='store_true', help='Load bounded full files from the same index or working-tree snapshot; never upload them')
    parser.add_argument('--backend-jar', type=Path, required=True)
    parser.add_argument('--java', default='java')
    parser.add_argument('--policy', type=Path, help='Explicit local JSON array of built-in ReviewRuleSettings; never fetched automatically')
    parser.add_argument('--format', choices=['text', 'json', 'sarif'], default='text')
    parser.add_argument('--output', type=Path, help='Create a new report; refuses to overwrite existing files')
    parser.add_argument('--fail-on', choices=['INFO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'], help='Opt-in local severity gate')
    args = parser.parse_args()
    if args.staged and args.include_untracked:
        parser.error('--include-untracked cannot be combined with --staged')
    repo = Path(git(args.repo, 'rev-parse', '--show-toplevel').decode().strip()).resolve()
    base, ancestor, head, files, gaps = collect(repo, args.base, args.staged, args.include_untracked, args.full_context)
    request = {'files': files}
    if args.policy:
        if args.policy.stat().st_size > 256 * 1024:
            raise PreflightError('policy_budget_exceeded')
        request['policy'] = json.loads(args.policy.read_text(encoding='utf-8'))
    payload = json.dumps(request, ensure_ascii=False).encode('utf-8')
    if len(payload) > 3 * 1024 * 1024:
        raise PreflightError('encoded_payload_budget_exceeded')
    jar = args.backend_jar.resolve(strict=True)
    raw = command([args.java, '-Xmx192m', '-Dfile.encoding=UTF-8',
                   '-Dloader.main=com.repoguard.agent.review.LocalReviewCommand', '-cp', str(jar),
                   'org.springframework.boot.loader.launch.PropertiesLauncher'], repo,
                  limit=8 * 1024 * 1024, data=payload, timeout=45)
    report = json.loads(raw)
    report.update(baseSha=base, mergeBaseSha=ancestor, headSha=head, diffSha256=hashlib.sha256(payload).hexdigest(),
                  changedFiles=len(files), untrackedFilesIncluded=args.include_untracked,
                  fullFileContextRequested=args.full_context,
                  fullFileContextLoaded=args.full_context and all(f.get('context', {}).get('status') in ('AVAILABLE', 'DELETED') for f in files),
                  files=[{k: v for k, v in f.items() if k not in ('patch', 'context')} for f in files],
                  gaps=gaps, codeowners=owners(repo, base, files))
    report['scanComplete'] = report['scanComplete'] and not gaps
    if args.format == 'text':
        text = f"Offline diff review: {len(files)} files, {len(report['findings'])} findings\n"
        text += f"Scope: local diff; no LLM; production policy not verified; untracked included={args.include_untracked}; full context requested={args.full_context}.\n"
        text += '\n'.join(f"{f['severity']} {f['ruleId']} {f['filePath']}:{f.get('lineNumber') or 1} {f['message']}"
                          for f in report['findings'])
        text += '\nCODEOWNERS: ' + json.dumps(report['codeowners'], ensure_ascii=False) + '\n'
        text += 'Scan complete within requested scope: ' + str(report['scanComplete']) + '\n'
    else:
        text = json.dumps(sarif(report) if args.format == 'sarif' else report, ensure_ascii=False, indent=2) + '\n'
    if args.output:
        with args.output.open('x', encoding='utf-8') as stream:
            stream.write(text)
    else:
        sys.stdout.reconfigure(encoding='utf-8')
        print(text, end='')
    if not report['scanComplete']:
        return 2
    severities = ['INFO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL']
    if args.fail_on and any(severities.index(f['severity']) >= severities.index(args.fail_on) for f in report['findings']):
        return 1
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as error:
        # Subprocess/parser exceptions can contain code or configuration; print only controlled diagnostics.
        messages = {
            'base_revision_unavailable': 'Base revision is unavailable locally. Fetch it separately or choose an existing commit.',
            'merge_base_unavailable': 'No local merge base is available. Check the base branch and shallow history.',
            'changed_file_budget_exceeded': 'More than 200 changed files. Review a smaller change set.',
            'total_diff_budget_exceeded': 'Combined diff/context exceeds 2 MiB. Reduce scope or omit --full-context.',
            'file_content_budget_exceeded': 'A file or patch exceeds 256 KiB. Reduce the input size.',
            'command_output_budget_exceeded': 'A local command exceeded its output budget. Reduce the input size.',
            'local_command_timeout': 'A local command timed out. Check Git/Java availability and reduce scope.',
            'local_command_failed': 'A local command failed. Verify Git access, the backend JAR version and policy input.',
            'diff_changed_during_collection': 'Files changed while collecting the snapshot. Save changes and retry.',
            'changed_file_list_changed_during_collection': 'The changed-file list changed during collection. Retry with a stable worktree.',
            'unmerged_or_unsupported_git_status': 'Resolve merge conflicts before running preflight.'
        }
        code = str(error) if isinstance(error, (PreflightError, ContentError)) else type(error).__name__
        print('Preflight failed: ' + messages.get(code, code), file=sys.stderr)
        sys.exit(2)
