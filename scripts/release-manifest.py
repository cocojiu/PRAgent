#!/usr/bin/env python3
"""Create and validate immutable release identities; provenance is verified with gh."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys

WORKFLOW = ".github/workflows/release-images.yml"
DIGEST = re.compile(r"sha256:[a-f0-9]{64}\Z")
SHA = re.compile(r"[a-f0-9]{40}\Z")
REPOSITORY = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\Z")
IMAGE_REPOSITORY = re.compile(r"[a-z0-9][a-z0-9.-]*(?::[0-9]+)?/[a-z0-9][a-z0-9_./-]*\Z")
TAG = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}\Z")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def positive(value, name):
    require(type(value) is int and value > 0, f"Invalid {name}")


def trusted_ref(ref):
    return ref in ("refs/heads/main", "refs/heads/master") or bool(re.fullmatch(r"refs/tags/v[A-Za-z0-9_.-]+", ref or ""))


def read_json(path):
    require(Path(path).stat().st_size <= 1024 * 1024, "Release manifest exceeds one MiB")
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write_json(path, value):
    Path(path).write_text(json.dumps(value, sort_keys=True, indent=2) + "\n", encoding="utf-8")


def validate(manifest, target=False):
    require(isinstance(manifest, dict), "Release manifest must be a JSON object")
    for field in ("source", "schema", "images"):
        require(isinstance(manifest.get(field), dict), f"Invalid manifest {field} object")
    require(manifest.get("formatVersion") == 1, "Unsupported release manifest")
    require(SHA.fullmatch(manifest.get("gitSha", "")), "Invalid release Git SHA")
    require(TAG.fullmatch(manifest.get("version", "")), "Invalid release version")
    source = manifest["source"]
    require(REPOSITORY.fullmatch(source.get("repository", "")), "Invalid source repository")
    require(source.get("workflow") == WORKFLOW and trusted_ref(source.get("ref")), "Untrusted release workflow or ref")
    require(source.get("event") in ("push", "workflow_dispatch"), "Untrusted release event")
    positive(source.get("runId"), "source run ID")
    positive(source.get("runAttempt"), "source run attempt")
    for key in ("requiredVersion", "minimumRollbackVersion"):
        positive(manifest["schema"].get(key), key)
    require(manifest["schema"]["minimumRollbackVersion"] <= manifest["schema"]["requiredVersion"], "Invalid schema range")
    require(manifest.get("checks") == {"backendScan": "passed", "frontendScan": "passed", "backendSbom": "attested", "frontendSbom": "attested"}, "Release checks did not pass")
    for role in ("backend", "frontend"):
        image = manifest["images"].get(role)
        require(isinstance(image, dict) and isinstance(image.get("source"), dict), "Invalid image identity object")
        require(image["source"]["repository"] == f"ghcr.io/{source['repository'].lower()}-{role}", "Unexpected source image repository")
        require(DIGEST.fullmatch(image["source"]["digest"]), "Invalid source digest")
        require(DIGEST.fullmatch(image["imageId"]), "Invalid platform image identity")
        require(image["platform"] == "linux/amd64", "Unsupported release platform")
        if target:
            require(isinstance(image.get("target"), dict), "Invalid target identity object")
            require(IMAGE_REPOSITORY.fullmatch(image["target"]["repository"]), "Invalid target repository")
            require(DIGEST.fullmatch(image["target"]["digest"]), "Invalid target digest")
    if target:
        approval = manifest.get("approval")
        require(isinstance(approval, dict), "Invalid deployment approval object")
        require(approval.get("environment") == "production" and approval.get("repository") == source["repository"], "Untrusted deployment approval")
        require(approval.get("workflow") == WORKFLOW and trusted_ref(approval.get("ref")), "Untrusted approval workflow or ref")
        require(SHA.fullmatch(approval.get("gitSha", "")), "Invalid approval SHA")
        positive(approval.get("runId"), "approval run ID")
        positive(approval.get("runAttempt"), "approval run attempt")
    return manifest


def inspection(image, reference):
    metadata = json.loads(subprocess.check_output(["docker", "image", "inspect", reference], text=True))[0]
    repository = reference.split("@", 1)[0] if "@" in reference else reference.rsplit(":", 1)[0]
    require(IMAGE_REPOSITORY.fullmatch(repository), "Invalid inspected image repository")
    digests = {ref for ref in metadata["RepoDigests"] if ref.startswith(repository + "@")}
    require(len(digests) == 1, "Inspected image digest is missing or ambiguous")
    immutable = next(iter(digests))
    pinned_digest = immutable.split("@", 1)[1]
    require(DIGEST.fullmatch(pinned_digest), "Invalid inspected image digest")
    require("@" not in reference or reference == immutable, "Local image differs from requested digest")
    platform = {"os": metadata["Os"], "architecture": metadata["Architecture"]}
    require(platform == {"os": "linux", "architecture": "amd64"}, "Unsupported inspected image platform")
    verbose = json.loads(subprocess.check_output(["docker", "manifest", "inspect", "--verbose", immutable], text=True))
    entries = verbose if isinstance(verbose, list) else [verbose]
    matching = [entry for entry in entries if entry["Descriptor"].get("platform") == platform]
    require(len(matching) == 1, "Registry platform image is missing or ambiguous")
    entry = matching[0]
    descriptor = entry["Descriptor"]
    require(DIGEST.fullmatch(descriptor["digest"]), "Invalid registry platform digest")
    if not isinstance(verbose, list):
        require(descriptor["digest"] == pinned_digest, "Registry manifest differs from requested digest")
    manifests = [entry[key] for key in ("OCIManifest", "SchemaV2Manifest") if key in entry]
    require(len(manifests) == 1 and manifests[0]["schemaVersion"] == 2, "Unsupported registry manifest")
    config_digest = manifests[0]["config"]["digest"]
    require(DIGEST.fullmatch(config_digest), "Invalid registry configuration digest")
    # Classic Docker IDs identify the config; containerd IDs identify a manifest
    # or index. Bind either local representation to the same immutable image.
    if metadata["Id"] != config_digest:
        local_descriptor = metadata.get("Descriptor", {})
        require(metadata["Id"] == local_descriptor.get("digest") and metadata["Id"] in (pinned_digest, descriptor["digest"]),
                "Local image is unrelated to the registry platform image")
    return {"Id": config_digest, "RuntimeId": metadata["Id"], "Os": metadata["Os"],
            "Architecture": metadata["Architecture"], "RepoDigests": [immutable]}


def inspect_images(args):
    write_json(args.output, [inspection("", reference) for reference in args.images])


def image_id(args):
    print(inspection("", args.image)["Id"])


def create_source(args):
    migrations = Path("repoguard-backend/src/main/resources/db/migration").glob("V*__*.sql")
    schema = max(int(path.name.split("__")[0][1:]) for path in migrations)
    manifest = {"formatVersion": 1, "gitSha": os.environ["GITHUB_SHA"], "version": os.environ["IMAGE_VERSION"],
                "source": {"repository": os.environ["GITHUB_REPOSITORY"], "workflow": WORKFLOW,
                           "runId": int(os.environ["GITHUB_RUN_ID"]), "runAttempt": int(os.environ["GITHUB_RUN_ATTEMPT"]),
                           "ref": os.environ["GITHUB_REF"], "event": os.environ["GITHUB_EVENT_NAME"]},
                "schema": {"requiredVersion": schema, "minimumRollbackVersion": 100},
                "checks": {"backendScan": "passed", "frontendScan": "passed", "backendSbom": "attested", "frontendSbom": "attested"}, "images": {}}
    for role in ("backend", "frontend"):
        repository = f"ghcr.io/{os.environ['GITHUB_REPOSITORY'].lower()}-{role}"
        digest = os.environ[f"{role.upper()}_DIGEST"]
        require(DIGEST.fullmatch(digest), "Invalid build digest")
        reference = f"{repository}@{digest}"
        subprocess.run(["docker", "pull", reference], check=True, stdout=sys.stderr)
        metadata = inspection(role, reference)
        manifest["images"][role] = {"source": {"repository": repository, "digest": digest}, "imageId": metadata["Id"],
                                     "platform": f"{metadata['Os']}/{metadata['Architecture']}"}
    write_json(args.output, validate(manifest))


def api(path):
    return json.loads(subprocess.check_output(["gh", "api", path], text=True))


def fetch(args):
    repository = os.environ["GITHUB_REPOSITORY"]
    require(REPOSITORY.fullmatch(repository) and re.fullmatch(r"[1-9][0-9]*", args.run_id), "Invalid artifact source")
    run = api(f"repos/{repository}/actions/runs/{args.run_id}")
    current_build = args.run_id == os.environ.get("GITHUB_RUN_ID") and args.kind == "source"
    require(run["path"] == WORKFLOW and run["head_repository"]["full_name"] == repository, "Untrusted source run")
    require(run["event"] in ("push", "workflow_dispatch"), "Untrusted source event")
    if not current_build:
        require(run["status"] == "completed" and run["conclusion"] == "success", "Source run has not succeeded")
    else:
        jobs = api(f"repos/{repository}/actions/runs/{args.run_id}/jobs?filter=latest&per_page=100")["jobs"]
        require(any(job["name"] == "Build and Push Images" and job["conclusion"] == "success" for job in jobs), "Current build job has not passed")
    prefix = "release-source-manifest" if args.kind == "source" else "approved-release-manifest"
    name = f"{prefix}-{run['id']}-{run['run_attempt']}"
    artifacts = api(f"repos/{repository}/actions/runs/{args.run_id}/artifacts?per_page=100")["artifacts"]
    require(any(a["name"] == name and not a["expired"] for a in artifacts), "Trusted manifest artifact unavailable")
    directory = Path(args.directory)
    directory.mkdir(parents=True, exist_ok=True)
    subprocess.run(["gh", "run", "download", args.run_id, "--repo", repository, "--name", name, "--dir", str(directory)], check=True)
    path = directory / ("release-source-manifest.json" if args.kind == "source" else "release-manifest.json")
    manifest = validate(read_json(path), args.kind == "approved")
    identity = manifest["source"] if args.kind == "source" else manifest["approval"]
    sha = manifest["gitSha"] if args.kind == "source" else identity["gitSha"]
    require(identity["repository"] == repository and identity["runId"] == run["id"] and identity["runAttempt"] == run["run_attempt"] and sha == run["head_sha"], "Manifest identity differs from source run")
    verified = json.loads(subprocess.check_output(["gh", "attestation", "verify", str(path), "--repo", repository,
                    "--signer-workflow", f"{repository}/{WORKFLOW}", "--source-digest", run["head_sha"],
                    "--source-ref", identity["ref"], "--deny-self-hosted-runners", "--format", "json"], text=True))
    invocation = f"https://github.com/{repository}/actions/runs/{run['id']}/attempts/{run['run_attempt']}"
    require(any(result["verificationResult"]["statement"]["predicate"]["runDetails"]["metadata"]["invocationId"] == invocation for result in verified), "Attestation belongs to a different run")
    if args.tag:
        require(manifest["version"] == args.tag, "Requested rollback tag differs from approved manifest")
    if args.kind == "source":
        require(manifest["gitSha"] == os.environ["GITHUB_SHA"], "Reused build does not match current release SHA")
        tag_input = os.environ.get("IMAGE_TAG_INPUT", "")
        require(not tag_input or manifest["version"] == tag_input, "Image tag override differs from reused build")
    print(str(path))


def resolve(args):
    run_id = os.environ.get("RELEASE_RUN_ID_INPUT", "")
    rollback = os.environ.get("DEPLOY_EXISTING_TAG", "")
    require(not rollback or run_id, "Rollback requires release_run_id of a successful approved manifest")
    if run_id:
        fetch(argparse.Namespace(run_id=run_id, kind="approved" if rollback else "source", directory=args.directory, tag=rollback))
    elif os.environ.get("GITHUB_EVENT_NAME") == "workflow_dispatch":
        runs = api(f"repos/{os.environ['GITHUB_REPOSITORY']}/actions/workflows/release-images.yml/runs?status=success&head_sha={os.environ['GITHUB_SHA']}&per_page=100")["workflow_runs"]
        for run in runs:
            artifacts = api(f"repos/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{run['id']}/artifacts?per_page=100")["artifacts"]
            if any(a["name"] == f"release-source-manifest-{run['id']}-{run['run_attempt']}" and not a["expired"] for a in artifacts):
                run_id = str(run["id"])
                fetch(argparse.Namespace(run_id=run_id, kind="source", directory=args.directory, tag=""))
                break
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.write(f"reuse_run_id={run_id}\nmanifest_kind={'approved' if rollback else 'source'}\n")


def promote(args):
    manifest = copy.deepcopy(validate(read_json(args.source)))
    metadata = read_json(args.inspections)
    require(len(metadata) == 2 and IMAGE_REPOSITORY.fullmatch(args.target_repository), "Invalid target inspection")
    for role, target in zip(("backend", "frontend"), metadata):
        image = manifest["images"][role]
        require(target["Id"] == image["imageId"] and f"{target['Os']}/{target['Architecture']}" == image["platform"], "Promotion changed the scanned platform image")
        digests = [ref.split("@", 1)[1] for ref in target["RepoDigests"] if ref.startswith(args.target_repository + "@")]
        require(len(set(digests)) == 1, "Target digest is missing or ambiguous")
        image["target"] = {"repository": args.target_repository, "digest": digests[0]}
    manifest["approval"] = {"environment": "production", "workflow": WORKFLOW, "repository": os.environ["GITHUB_REPOSITORY"],
                            "gitSha": os.environ["GITHUB_SHA"], "ref": os.environ["GITHUB_REF"],
                            "runId": int(os.environ["GITHUB_RUN_ID"]), "runAttempt": int(os.environ["GITHUB_RUN_ATTEMPT"])}
    write_json(args.output, validate(manifest, True))


def deployment_env(args):
    data = Path(args.manifest).read_bytes()
    require(re.fullmatch(r"[a-f0-9]{64}", args.sha256) and hashlib.sha256(data).hexdigest() == args.sha256, "Manifest integrity mismatch")
    manifest = validate(read_json(args.manifest), True)
    require(str(manifest["approval"]["runId"]) == args.approval_run_id, "Unexpected approval run")
    values = {"EXPECTED_RELEASE_SHA": manifest["gitSha"], "EXPECTED_RELEASE_VERSION": manifest["version"],
              "EXPECTED_SCHEMA_VERSION": str(manifest["schema"]["requiredVersion"])}
    for role in ("backend", "frontend"):
        image = manifest["images"][role]
        require(image["target"]["repository"] == args.target_repository, "Unexpected deployment registry")
        values[f"{role.upper()}_IMAGE"] = f"{image['target']['repository']}@{image['target']['digest']}"
        values[f"EXPECTED_{role.upper()}_IMAGE_ID"] = image["imageId"]
    print("\n".join(f"{key}={shlex.quote(value)}" for key, value in values.items()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    source = commands.add_parser("create-source"); source.add_argument("--output", required=True); source.set_defaults(function=create_source)
    download = commands.add_parser("fetch"); download.add_argument("--run-id", required=True); download.add_argument("--kind", choices=("source", "approved"), required=True); download.add_argument("--directory", required=True); download.add_argument("--tag", default=""); download.set_defaults(function=fetch)
    resolver = commands.add_parser("resolve"); resolver.add_argument("--directory", required=True); resolver.set_defaults(function=resolve)
    promotion = commands.add_parser("promote"); promotion.add_argument("--source", required=True); promotion.add_argument("--inspections", required=True); promotion.add_argument("--target-repository", required=True); promotion.add_argument("--output", required=True); promotion.set_defaults(function=promote)
    images = commands.add_parser("inspect-images"); images.add_argument("--output", required=True); images.add_argument("images", nargs="+"); images.set_defaults(function=inspect_images)
    identity = commands.add_parser("image-id"); identity.add_argument("--image", required=True); identity.set_defaults(function=image_id)
    deployment = commands.add_parser("deployment-env"); deployment.add_argument("--manifest", required=True); deployment.add_argument("--sha256", required=True); deployment.add_argument("--target-repository", required=True); deployment.add_argument("--approval-run-id", required=True); deployment.set_defaults(function=deployment_env)
    args = parser.parse_args()
    try:
        args.function(args)
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError) as error:
        parser.exit(1, f"Release manifest rejected: {error}\n")


if __name__ == "__main__":
    main()
