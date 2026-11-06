#!/usr/bin/env python3
"""Bind a production-registry Loki image to its independently signed source."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re

WORKFLOW = '.github/workflows/production-observability-log-stack-upgrade.yml'
DIGEST = re.compile(r'sha256:[a-f0-9]{64}\Z')
SHA = re.compile(r'[a-f0-9]{40}\Z')
TARGET_REPOSITORY = re.compile(r'[a-z0-9][a-z0-9.-]*\.aliyuncs\.com/[a-z0-9][a-z0-9._-]*/[a-z0-9][a-z0-9._-]*\Z')


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def reference(target):
    return target['image']['repository'] + '@' + target['image']['digest']


def validate(target, source, manifest_sha, repository, deployment_sha, release_id, target_repository):
    require(isinstance(deployment_sha, str) and isinstance(manifest_sha, str) and isinstance(target_repository, str)
        and SHA.fullmatch(deployment_sha) and re.fullmatch(r'[a-f0-9]{64}', manifest_sha)
        and TARGET_REPOSITORY.fullmatch(target_repository or ''), 'invalid_loki_target_context')
    require(isinstance(release_id, str) and re.fullmatch(r'[1-9][0-9]{4,19}-[1-9][0-9]{0,3}', release_id), 'invalid_loki_target_release')
    run_id, attempt = (int(item) for item in release_id.split('-'))
    require(isinstance(target, dict) and set(target) == {'formatVersion', 'sourceManifestSha256', 'source',
        'sourceImage', 'image', 'approval'} and type(target['formatVersion']) is int and target['formatVersion'] == 1,
        'unsupported_loki_target')
    require(target['sourceManifestSha256'] == manifest_sha and target['source'] == source['source']
        and target['sourceImage'] == source['image'] and source['source']['repository'] == repository,
        'loki_target_source_mismatch')
    expected = {'environment': 'production', 'workflow': WORKFLOW, 'repository': repository,
        'gitSha': deployment_sha, 'ref': 'refs/heads/main', 'runId': run_id, 'runAttempt': attempt}
    require(target['approval'] == expected and type(target['approval']['runId']) is int
        and type(target['approval']['runAttempt']) is int, 'loki_target_approval_mismatch')
    image = target['image']
    require(isinstance(image, dict) and set(image) == {'repository', 'digest', 'imageId', 'runtimeId', 'platform'}
        and image['repository'] == target_repository and image['platform'] == source['image']['platform'] == 'linux/amd64',
        'loki_target_repository_or_platform_mismatch')
    require(all(isinstance(image.get(key), str) and DIGEST.fullmatch(image[key])
        for key in ('digest', 'imageId', 'runtimeId')), 'invalid_loki_target_digest')
    require(image['imageId'] == source['image']['imageId'], 'loki_target_configuration_changed')
    return target


def create(args):
    spec = importlib.util.spec_from_file_location('loki_release', Path(__file__).with_name('loki-release.py'))
    release = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(release)
    source = release.read_json(args.source)
    raw = Path(args.source).read_bytes()
    identity = source['source']
    release.validate_source(source, os.environ['GITHUB_REPOSITORY'], identity['gitSha'], identity['runId'], identity['runAttempt'])
    require(os.environ['GITHUB_REF'] == 'refs/heads/main', 'loki_target_requires_main')
    deployment_sha = os.environ['GITHUB_SHA']
    run_id, attempt = int(os.environ['GITHUB_RUN_ID']), int(os.environ['GITHUB_RUN_ATTEMPT'])
    inspections = release.read_json(args.inspections)
    require(isinstance(inspections, list) and len(inspections) == 1, 'one_loki_target_inspection_required')
    inspected = inspections[0]
    require(inspected['Id'] == source['image']['imageId'] and inspected['Os'] == 'linux'
        and inspected['Architecture'] == 'amd64', 'loki_target_configuration_changed')
    digests = inspected['RepoDigests']
    require(isinstance(digests, list) and len(digests) == 1
        and digests[0].startswith(args.target_repository + '@'), 'loki_target_inspection_repository_mismatch')
    target = {'formatVersion': 1, 'sourceManifestSha256': hashlib.sha256(raw).hexdigest(),
        'source': identity, 'sourceImage': source['image'], 'image': {'repository': args.target_repository,
            'digest': digests[0].split('@', 1)[1], 'imageId': inspected['Id'], 'runtimeId': inspected['RuntimeId'],
            'platform': 'linux/amd64'}, 'approval': {'environment': 'production', 'workflow': WORKFLOW,
            'repository': os.environ['GITHUB_REPOSITORY'], 'gitSha': deployment_sha, 'ref': 'refs/heads/main',
            'runId': run_id, 'runAttempt': attempt}}
    validate(target, source, hashlib.sha256(raw).hexdigest(), os.environ['GITHUB_REPOSITORY'], deployment_sha,
        str(run_id) + '-' + str(attempt), args.target_repository)
    with Path(args.output).open('x', encoding='utf-8') as output:
        json.dump(target, output, sort_keys=True, indent=2)
        output.write('\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source')
    parser.add_argument('--inspections')
    parser.add_argument('--target-repository')
    parser.add_argument('--output')
    parser.add_argument('--check-repository')
    args = parser.parse_args()
    if args.check_repository is not None:
        require(not any((args.source, args.inspections, args.target_repository, args.output))
            and TARGET_REPOSITORY.fullmatch(args.check_repository), 'invalid_loki_target_repository')
    else:
        if not all((args.source, args.inspections, args.target_repository, args.output)):
            parser.error('source, inspections, target-repository and output are required')
        create(args)


if __name__ == '__main__':
    main()
