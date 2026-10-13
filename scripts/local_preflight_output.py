"""Safe presentation and exclusive publication of local preflight reports."""
import argparse
import json
import os
from pathlib import Path
import sys
import tempfile
import unicodedata


MESSAGES = {
    'invalid_cli_arguments': 'Invalid options. Run --help for supported combinations; pass scoped credentials only through private stdin.',
    'base_revision_unavailable': 'Base revision is unavailable locally. Fetch it separately or choose an existing commit.',
    'invalid_base_revision': 'The local base revision is invalid. Select an existing commit.',
    'merge_base_unavailable': 'No local merge base is available. Check the base branch and shallow history.',
    'changed_file_budget_exceeded': 'More than 200 changed files. Review a smaller change set.',
    'total_diff_budget_exceeded': 'Combined diff/context exceeds 2 MiB. Reduce scope or omit --full-context.',
    'file_content_budget_exceeded': 'A file or patch exceeds 256 KiB. Reduce the input size.',
    'command_output_budget_exceeded': 'A local command exceeded its output budget. Reduce the input size.',
    'local_command_timeout': 'A local command timed out. Check Git/Java availability and reduce scope.',
    'local_command_failed': 'A local command failed. Verify Git access, the backend JAR version and policy input.',
    'diff_changed_during_collection': 'Files changed during collection. Retry with a stable worktree.',
    'changed_file_list_changed_during_collection': 'The changed-file list changed during collection. Retry with a stable worktree.',
    'head_changed_during_collection': 'HEAD changed during collection. Retry with a stable repository.',
    'file_changed_during_read': 'A file changed while being read. Retry with a stable worktree.',
    'unmerged_or_unsupported_git_status': 'Resolve merge conflicts and unsupported Git changes before preflight.',
    'unsupported_git_path': 'A changed path is unsupported. Use repository-relative regular file paths.',
    'policy_budget_exceeded': 'The local policy exceeds 256 KiB. Reduce its size.',
    'encoded_payload_budget_exceeded': 'The encoded request exceeds 3 MiB. Review a smaller change set.',
    'online_diff_scope_incomplete': 'Online preflight requires a complete supported diff. Remove unsupported changes or review them offline.',
    'invalid_preflight_origin': 'Use an HTTPS origin without credentials, a path, query parameters or fragment.',
    'scoped_preflight_credential_required': 'Provide a short-lived scoped preflight credential through private stdin.',
    'private_credential_stdin_required': 'Pipe the scoped credential through private stdin; do not enter it in command-line arguments.',
    'preflight_credential_rejected': 'The scoped credential was rejected. Obtain a valid credential for the same user, session and repository.',
    'preflight_permission_denied': 'Preflight permission was denied. Verify the user, repository scope and model opt-in permissions.',
    'preflight_version_or_request_conflict': 'The request conflicts with policy or an existing preview. Recheck policy and preview state before submitting again.',
    'preflight_rate_limited': 'A rate or request limit was reached. Wait for the allowed window; this CLI does not retry automatically.',
    'preflight_service_unavailable': 'The preflight service is unavailable. Verify the HTTPS origin, server opt-in and service readiness.',
    'preflight_service_failure': 'The preflight service failed. Ask the operator to check service health; no automatic retry was sent.',
    'preflight_request_rejected': 'The service rejected the request. Verify the CLI/server versions and the supported diff scope.',
    'preflight_http_failure': 'An unexpected HTTP response was received. Verify the HTTPS origin and service readiness.',
    'preflight_redirect_refused': 'The service attempted a redirect. Use the correct HTTPS origin; credentials are never forwarded.',
    'preflight_connection_failed': 'The HTTPS connection failed. Check network access and the HTTPS origin.',
    'preflight_connection_timeout': 'The HTTPS request timed out. Check network and service health; no automatic retry was sent.',
    'preflight_tls_verification_failed': 'HTTPS certificate verification failed. Check the hostname and trusted certificate chain; TLS verification remains required.',
    'preflight_request_budget_exceeded': 'The request exceeds the transport budget. Review a smaller change set.',
    'preflight_response_budget_exceeded': 'The response exceeds the transport budget. Review a smaller change set.',
    'invalid_preflight_response': 'The service response is invalid. Verify compatible CLI and server versions.',
    'unverified_preflight_response': 'The response does not match the request or required policy contract. It was refused.',
    'preflight_credential_echo_refused': 'The service response echoed a credential. The response was refused; contact the operator privately.',
    'invalid_private_preflight_input': 'The private transport input is invalid. Use the supported CLI and private credential input.',
    'report_already_exists': 'The report destination already exists. Choose a new file; existing files are never overwritten.',
    'report_write_failed': 'The report could not be written. Check the output directory, free space and permissions.',
    'report_atomic_publish_unavailable': 'The filesystem cannot publish the report atomically without overwriting. Choose a supported local filesystem.',
    'report_cleanup_failed': 'A temporary report file could not be removed. Check output-directory permissions; a published report is complete.',
    'preflight_interrupted': 'Preflight was interrupted. Check for an already published report before running it again.',
    'preflight_unexpected_failure': 'Preflight failed unexpectedly. Check the local inputs and compatible CLI/server versions.',
}


class ReportOutputError(Exception):
    pass


def terminal_text(value):
    """Escape display controls, including bidi controls, without changing report data."""
    result = []
    for char in str(value):
        if unicodedata.category(char) in ('Cc', 'Cf', 'Cs', 'Zl', 'Zp'):
            number = ord(char)
            result.append(('\\x%02x' if number <= 255 else '\\u%04x' if number <= 65535 else '\\U%08x') % number)
        else:
            result.append(char)
    return ''.join(result)


def diagnostic_format(arguments):
    selected = 'text'
    for index, argument in enumerate(arguments):
        if argument == '--format' and index + 1 < len(arguments):
            selected = arguments[index + 1]
        elif argument.startswith('--format='):
            selected = argument.partition('=')[2]
    return 'json' if selected in ('json', 'sarif') else 'text'


def emit_diagnostic(code, arguments=None):
    # Never echo arguments, exception text, response bodies, URLs or credentials.
    code = code if code in MESSAGES else 'preflight_unexpected_failure'
    arguments = sys.argv[1:] if arguments is None else arguments
    if diagnostic_format(arguments) == 'json':
        text = json.dumps({'success': False, 'error': {'code': code, 'message': MESSAGES[code]}, 'exitCode': 2})
    else:
        text = 'Preflight failed [' + code + ']: ' + MESSAGES[code]
    print(text, file=sys.stderr)


class DiagnosticArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        emit_diagnostic('invalid_cli_arguments')
        raise SystemExit(2)


def validate_report_destination(path):
    path = Path(path).absolute()
    try:
        path.lstat()
    except FileNotFoundError:
        pass
    except OSError:
        raise ReportOutputError('report_write_failed') from None
    else:
        raise ReportOutputError('report_already_exists')
    if not path.parent.is_dir():
        raise ReportOutputError('report_write_failed')
    return path


def publish_report(path, text):
    """Publish only flushed complete data, with an exclusive destination creation."""
    path = validate_report_destination(path)
    temporary = None
    try:
        try:
            descriptor, name = tempfile.mkstemp(prefix='.repoguard-report-', suffix='.tmp', dir=path.parent)
            temporary = Path(name)
            with os.fdopen(descriptor, 'w', encoding='utf-8', newline='\n') as stream:
                stream.write(text)
                stream.flush()
                os.fsync(stream.fileno())
        except (OSError, UnicodeError):
            raise ReportOutputError('report_write_failed') from None
        try:
            if os.name == 'nt':
                # Windows rename refuses an existing destination, including a competing publisher.
                os.rename(temporary, path)
            else:
                # POSIX rename replaces files; a hard link creates the destination exclusively.
                os.link(temporary, path)
        except FileExistsError:
            raise ReportOutputError('report_already_exists') from None
        except OSError:
            raise ReportOutputError('report_atomic_publish_unavailable') from None
    finally:
        active_exception = sys.exc_info()[0] is not None
        if temporary is not None:
            try:
                temporary.unlink()
            except FileNotFoundError:
                pass
            except OSError:
                if not active_exception:
                    raise ReportOutputError('report_cleanup_failed') from None
