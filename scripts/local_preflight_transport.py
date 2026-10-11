#!/usr/bin/env python3
"""Private-stdin HTTPS transport for optional scoped preflight; no business credentials or retries."""
import base64
import hashlib
import json
import re
import ssl
import sys
import time
from urllib import error, parse, request

MAX_REQUEST = 3 * 1024 * 1024
MAX_RESPONSE = 2 * 1024 * 1024
MAX_TOKEN = 4096


class TransportError(Exception):
    pass


def origin_url(value):
    if not isinstance(value, str) or len(value) > 2048 or any(ord(c) < 33 for c in value):
        raise TransportError('invalid_preflight_origin')
    try:
        uri = parse.urlsplit(value)
        port = uri.port
    except ValueError:
        raise TransportError('invalid_preflight_origin') from None
    if (uri.scheme != 'https' or not uri.hostname or uri.username is not None
            or uri.password is not None or uri.path not in ('', '/')
            or uri.query or uri.fragment or '%' in uri.netloc
            or (port is not None and not 1 <= port <= 65535)):
        raise TransportError('invalid_preflight_origin')
    return parse.urlunsplit(('https', uri.netloc, '', '', ''))


def scoped_credential(value):
    if (not isinstance(value, str) or len(value) > MAX_TOKEN
            or not re.fullmatch(r'rgpf\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', value)):
        raise TransportError('scoped_preflight_credential_required')
    return value


def credential_from_stdin(stream):
    if stream.isatty():
        raise TransportError('private_credential_stdin_required')
    value = stream.readline(MAX_TOKEN + 2)
    return scoped_credential(value.rstrip('\r\n'))


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def safe_report(envelope, body_sha, repository, llm_requested, credential):
    if not isinstance(envelope, dict) or envelope.get('success') is not True:
        raise TransportError('invalid_preflight_response')
    data = envelope.get('data')
    if (not isinstance(data, dict) or data.get('scope') != 'ONLINE_PREFLIGHT'
            or data.get('productionPolicyVerified') is not True
            or data.get('repository') != repository or data.get('requestSha256') != body_sha
            or type(data.get('scanComplete')) is not bool or type(data.get('llmExecuted')) is not bool
            or (data['llmExecuted'] and not llm_requested)
            or not re.fullmatch('[a-f0-9]{64}', str(data.get('policyFingerprint', '')))):
        raise TransportError('unverified_preflight_response')
    findings = data.get('findings')
    if not isinstance(findings, list) or len(findings) > 2000:
        raise TransportError('invalid_preflight_response')
    projected = []
    for row in findings:
        if not isinstance(row, dict) or row.get('severity') not in ('INFO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'):
            raise TransportError('invalid_preflight_response')
        value = {'severity': row['severity']}
        for field, limit in [('ruleId', 128), ('filePath', 1024), ('message', 4096),
                             ('recommendation', 4096), ('fingerprint', 255)]:
            text = row.get(field)
            if not isinstance(text, str) or len(text) > limit or (field != 'recommendation' and not text):
                raise TransportError('invalid_preflight_response')
            if credential in text:
                raise TransportError('preflight_credential_echo_refused')
            value[field] = text
        line = row.get('lineNumber')
        if line is not None and (type(line) is not int or not 1 <= line <= 2147483647):
            raise TransportError('invalid_preflight_response')
        value['lineNumber'] = line
        projected.append(value)
    # Exclude raw evidence, snippets, prompts, provider payloads, credentials and arbitrary response fields.
    return {'scope': 'ONLINE_PREFLIGHT', 'policySource': 'SERVER_REPOSITORY_POLICY',
            'productionPolicyVerified': True, 'policyFingerprint': data['policyFingerprint'],
            'requestSha256': body_sha, 'llmExecuted': data['llmExecuted'],
            'scanComplete': data['scanComplete'], 'findings': projected}


def send(private_input):
    if not isinstance(private_input, dict):
        raise TransportError('invalid_private_preflight_input')
    origin = origin_url(private_input.get('origin'))
    token = scoped_credential(private_input.get('credential'))
    try:
        body = base64.b64decode(private_input['payloadBase64'], validate=True)
        payload = json.loads(body)
    except (KeyError, TypeError, ValueError):
        raise TransportError('invalid_private_preflight_input') from None
    if len(body) > MAX_REQUEST:
        raise TransportError('preflight_request_budget_exceeded')
    if (not isinstance(payload, dict) or type(payload.get('llmRequested')) is not bool
            or not re.fullmatch(r'[A-Za-z0-9_-]{1,39}/[A-Za-z0-9_.-]{1,100}', str(payload.get('repository', '')))):
        raise TransportError('invalid_private_preflight_input')
    body_sha = hashlib.sha256(body).hexdigest()
    headers = {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json',
               'Accept': 'application/json', 'Accept-Encoding': 'identity', 'Idempotency-Key': body_sha}
    opener = request.build_opener(NoRedirect(), request.HTTPSHandler(context=ssl.create_default_context()))
    started = time.monotonic()
    try:
        with opener.open(request.Request(origin + '/api/v1/preflight/preview', data=body,
                                         headers=headers, method='POST'), timeout=40) as response:
            length = response.headers.get('Content-Length')
            if (response.status != 200 or response.headers.get_content_type() != 'application/json'
                    or (length is not None and (not length.isdecimal() or int(length) > MAX_RESPONSE))):
                raise TransportError('invalid_preflight_response')
            raw = response.read(MAX_RESPONSE + 1)
            if len(raw) > MAX_RESPONSE:
                raise TransportError('preflight_response_budget_exceeded')
            report = safe_report(json.loads(raw), body_sha, payload['repository'], payload['llmRequested'], token)
    except error.HTTPError as failure:
        status = failure.code
        failure.close()
        if status in (301, 302, 303, 307, 308):
            raise TransportError('preflight_redirect_refused') from None
        if status in (401, 403):
            raise TransportError('preflight_credential_rejected') from None
        if status == 409:
            raise TransportError('preflight_version_or_request_conflict') from None
        raise TransportError('preflight_http_failure') from None
    except (error.URLError, TimeoutError, OSError):
        raise TransportError('preflight_connection_failed') from None
    except (ValueError, UnicodeError):
        raise TransportError('invalid_preflight_response') from None
    return {'report': report, 'transport': {'requestBytes': len(body), 'responseBytes': len(raw),
            'elapsedMs': round((time.monotonic() - started) * 1000, 2), 'tlsCertificateVerified': True,
            'redirectsFollowed': False, 'httpRetries': 0, 'idempotencyKey': body_sha}}


def main():
    # The parent CLI kills this process after 45 seconds, including TLS and complete response reads.
    raw = sys.stdin.buffer.read(4 * 1024 * 1024 + 8193)
    if len(raw) > 4 * 1024 * 1024 + 8192:
        raise TransportError('preflight_request_budget_exceeded')
    return send(json.loads(raw))


if __name__ == '__main__':
    try:
        result = main()
    except TransportError as failure:
        result = {'error': str(failure)}
    except Exception:
        result = {'error': 'invalid_preflight_response'}
    sys.stdout.reconfigure(encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False))
