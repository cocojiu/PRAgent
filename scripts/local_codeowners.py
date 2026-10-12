"""Bounded offline CODEOWNERS recommendations; external identities are never treated as tenant users."""
import fnmatch
import re


def prepare_pattern(pattern):
    rooted = pattern.startswith('/') or '/' in pattern.rstrip('/')
    directory = pattern.endswith('/')
    tokens = pattern.strip('/').split('/')
    if not rooted:
        tokens.insert(0, '**')
    if pattern.rstrip('/').endswith('/**'):
        tokens.append('*')  # A trailing /** requires at least one descendant.
    descend = directory or not any(char in tokens[-1] for char in '*?')
    return directory, descend, tuple((token, re.compile(fnmatch.translate(token))
        if token != '**' and any(char in token for char in '*?[') else None) for token in tokens)


def match_prepared(prepared, parts):
    directory, descend, tokens = prepared
    reachable = {0}
    for token, matcher in tokens:
        if token == '**':
            reachable = set(range(min(reachable), len(parts) + 1))
        else:
            reachable = {index + 1 for index in reachable if index < len(parts)
                and (matcher.fullmatch(parts[index]) is not None if matcher else token == parts[index])}
        if not reachable:
            return False
    return (not directory and len(parts) in reachable) or (descend and min(reachable) < len(parts))


def matches(pattern, path):
    return match_prepared(prepare_pattern(pattern), path.split('/'))


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
        rules.append((pattern, list(dict.fromkeys(owners)), number, prepare_pattern(pattern)))
    totals = {}
    for path in dict.fromkeys(paths):
        matched = None
        parts = path.split('/')
        for pattern, owners, number, prepared in reversed(rules):
            if match_prepared(prepared, parts):
                matched = {'path': path, 'pattern': pattern, 'line': number, 'owners': owners}
                break
        row = matched or {'path': path, 'owners': []}
        result.append(row)
        for owner in row['owners']:
            totals[owner] = totals.get(owner, 0) + 1
    candidates = [{'externalIdentity': owner, 'coveredFiles': count}
                  for owner, count in sorted(totals.items(), key=lambda entry: (-entry[1], entry[0]))[:3]]
    return {'source': source, 'status': 'PARTIAL_SYNTAX' if warnings else 'LOCAL_RECOMMENDATION',
            'membershipVerified': False, 'automaticallyAssigned': False, 'recommendations': candidates,
            'paths': result, 'uncovered': [r['path'] for r in result if not r['owners']], 'warnings': warnings}
