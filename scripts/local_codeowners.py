"""Bounded offline CODEOWNERS recommendations; external identities are never treated as tenant users."""
from functools import lru_cache
import fnmatch
import re


def matches(pattern, path):
    rooted = pattern.startswith('/') or '/' in pattern.rstrip('/')
    directory = pattern.endswith('/')
    tokens = pattern.strip('/').split('/')
    parts = path.split('/')
    if not rooted:
        tokens.insert(0, '**')
    # A literal final component may name a directory as well as a file.
    descend = directory or not any(char in tokens[-1] for char in '*?')

    @lru_cache(maxsize=8192)
    def visit(i, j):
        if i == len(tokens):
            return (not directory and j == len(parts)) or (descend and j < len(parts))
        if tokens[i] == '**':
            return visit(i + 1, j) or (j < len(parts) and visit(i, j + 1))
        return j < len(parts) and fnmatch.fnmatchcase(parts[j], tokens[i]) and visit(i + 1, j + 1)
    return visit(0, 0)


def recommend(content, paths, source):
    warnings, rules, result = [], [], []
    if len(content.encode('utf-8')) > 128 * 1024 or len(content.splitlines()) > 2000:
        return {'source': source, 'status': 'BUDGET_EXCEEDED', 'recommendations': [], 'uncovered': paths}
    for number, raw in enumerate(content.splitlines(), 1):
        line = raw.split('#', 1)[0].strip()
        if not line:
            continue
        columns = line.split()
        pattern, owners = columns[0], columns[1:]
        if (len(pattern) > 256 or pattern.count('/') > 32 or any(c in pattern for c in '![]\\')
                or '..' in pattern.split('/') or len(owners) > 20
                or any(not re.fullmatch(r'@[\w-]+(?:/[\w-]+)?|[^\s@]+@[^\s@]+\.[^\s@]+', o, re.ASCII) for o in owners)):
            warnings.append({'line': number, 'reason': 'unsupported_pattern_or_identity'})
            continue
        rules.append((pattern, owners, number))
    totals = {}
    for path in paths:
        matched = None
        for pattern, owners, number in rules:
            if matches(pattern, path):
                matched = {'path': path, 'pattern': pattern, 'line': number, 'owners': owners}
        row = matched or {'path': path, 'owners': []}
        result.append(row)
        for owner in row['owners']:
            totals[owner] = totals.get(owner, 0) + 1
    candidates = [{'externalIdentity': owner, 'coveredFiles': count}
                  for owner, count in sorted(totals.items(), key=lambda entry: (-entry[1], entry[0]))[:3]]
    return {'source': source, 'status': 'PARTIAL_SYNTAX' if warnings else 'LOCAL_RECOMMENDATION',
            'membershipVerified': False, 'automaticallyAssigned': False, 'recommendations': candidates,
            'paths': result, 'uncovered': [r['path'] for r in result if not r['owners']], 'warnings': warnings}
