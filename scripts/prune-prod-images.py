#!/usr/bin/env python3
"""Reviewable cleanup of old RepoGuard images. Volumes, build caches and other repositories are excluded."""
import argparse
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

IMAGE_ID = re.compile(r"sha256:[0-9a-f]{64}\Z")
REVISION = re.compile(r"[0-9a-f]{40}\Z")


def docker(*arguments):
    result = subprocess.run(["docker", *arguments], capture_output=True, text=True, timeout=90)
    if result.returncode:
        raise RuntimeError("docker_operation_failed")
    return result.stdout


def timestamp(value):
    bounded = re.sub(r"(\.[0-9]{6})[0-9]+(?=Z|[+-])", r"\1", value)
    return datetime.fromisoformat(bounded.replace("Z", "+00:00"))


def repository(reference):
    return reference.split("@", 1)[0] if "@" in reference else reference.rsplit(":", 1)[0]


@contextmanager
def deployment_lock(root):
    lock = root / ".deploy.lock"
    descriptor = os.open(lock, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        info = os.fstat(descriptor)
        if info.st_uid != os.geteuid() or info.st_mode & 0o022:
            raise RuntimeError("unsafe_deployment_lock")
        fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield
    finally:
        os.close(descriptor)


def protected_references(root):
    directory = root / ".deploy-state"
    if directory.is_symlink() or not directory.is_dir():
        raise RuntimeError("release_state_unavailable")
    paths = sorted(directory.glob("release-manifest-*.json"))
    if not paths or len(paths) > 64:
        raise RuntimeError("invalid_manifest_count")
    references, repositories, hashes = set(), set(), {}
    for path in paths:
        if path.is_symlink() or path.stat().st_size > 262144 or path.stat().st_mode & 0o022:
            raise RuntimeError("unsafe_release_manifest")
        raw = path.read_bytes()
        manifest = json.loads(raw)
        if manifest.get("formatVersion") != 1 or set(manifest.get("images", {})) != {"backend", "frontend"}:
            raise RuntimeError("unsupported_release_manifest")
        hashes[path.name] = hashlib.sha256(raw).hexdigest()
        for image in manifest["images"].values():
            identity = image["imageId"]
            if not IMAGE_ID.fullmatch(identity):
                raise RuntimeError("invalid_protected_image")
            references.add(identity)
            for origin in ("source", "target"):
                item = image[origin]
                if not IMAGE_ID.fullmatch(item["digest"]):
                    raise RuntimeError("invalid_protected_digest")
                repo = item["repository"]
                # Project repositories only; a malformed manifest cannot widen cleanup to a shared service.
                if repo not in {"ghcr.io/cocojiu/pragent-backend", "ghcr.io/cocojiu/pragent-frontend"} \
                        and not re.fullmatch(r"[a-z0-9.-]+\.aliyuncs\.com/cocojiu/pragent", repo):
                    raise RuntimeError("unexpected_project_repository")
                repositories.add(repo)
                references.update((item["digest"], repo + "@" + item["digest"]))
    container_ids = docker("ps", "-aq").split()
    if container_ids:
        for container in json.loads(docker("container", "inspect", *container_ids)):
            references.update((container["Image"], container["Config"]["Image"]))
    return references, repositories, hashes


def inventory():
    ids = sorted(set(docker("image", "ls", "-q", "--no-trunc").split()))
    images = []
    for start in range(0, len(ids), 30):
        for value in json.loads(docker("image", "inspect", *ids[start:start + 30])):
            labels = value.get("Config", {}).get("Labels") or {}
            images.append({"id": value["Id"], "references": sorted(set(
                (value.get("RepoTags") or []) + (value.get("RepoDigests") or []))),
                "created": value["Created"], "size": value.get("Size", 0),
                "revision": labels.get("org.opencontainers.image.revision", ""),
                "version": labels.get("org.opencontainers.image.version", "")})
    return images


def eligible(image, protected, repositories, newest, cutoff):
    return (IMAGE_ID.fullmatch(image["id"]) and REVISION.fullmatch(image["revision"])
            and image["version"].startswith("main-") and image["references"]
            and all(repository(ref) in repositories for ref in image["references"])
            and image["id"] not in protected and not set(image["references"]) & protected
            and image["revision"] not in newest and timestamp(image["created"]) < cutoff)


def plan(root, minimum_age_days, maximum_images):
    protected, repositories, hashes = protected_references(root)
    images = inventory()
    project = [value for value in images if REVISION.fullmatch(value["revision"])
               and value["references"] and all(repository(ref) in repositories for ref in value["references"])]
    newest = []
    for image in sorted(project, key=lambda value: timestamp(value["created"]), reverse=True):
        if image["revision"] not in newest:
            newest.append(image["revision"])
        if len(newest) >= 3:
            break
    now = datetime.now(timezone.utc)
    cutoff = now - timedelta(days=minimum_age_days)
    candidates = [value for value in project if eligible(value, protected, repositories, newest, cutoff)]
    candidates.sort(key=lambda value: (timestamp(value["created"]), value["id"]))
    selected = candidates[:maximum_images]
    return {"formatVersion": 1, "root": str(root), "createdAt": now.isoformat(),
            "minimumAgeDays": minimum_age_days, "maximumImages": maximum_images,
            "protectedManifests": hashes, "protectedReferences": sorted(protected),
            "retainedRevisions": newest, "eligibleImages": len(candidates), "images": selected,
            "uncompressedSizeUpperBound": sum(value["size"] for value in selected)}


def apply(root, path, expected):
    if path.is_symlink() or path.stat().st_size > 1048576:
        raise RuntimeError("unsafe_cleanup_plan")
    raw = path.read_bytes()
    if not re.fullmatch(r"[0-9a-f]{64}", expected or "") or hashlib.sha256(raw).hexdigest() != expected:
        raise RuntimeError("cleanup_plan_hash_mismatch")
    reviewed = json.loads(raw)
    if reviewed.get("formatVersion") != 1 or reviewed.get("root") != str(root):
        raise RuntimeError("cleanup_plan_scope_mismatch")
    if type(reviewed.get("minimumAgeDays")) is not int or not 7 <= reviewed["minimumAgeDays"] <= 365 \
            or type(reviewed.get("maximumImages")) is not int or not 1 <= reviewed["maximumImages"] <= 20 \
            or not isinstance(reviewed.get("images"), list) or len(reviewed["images"]) > 20:
        raise RuntimeError("cleanup_plan_bounds_invalid")
    age = datetime.now(timezone.utc) - timestamp(reviewed["createdAt"])
    if age < timedelta() or age > timedelta(minutes=30):
        raise RuntimeError("cleanup_plan_expired")
    fresh = plan(root, reviewed["minimumAgeDays"], reviewed["maximumImages"])
    if reviewed != {**fresh, "createdAt": reviewed["createdAt"]}:
        raise RuntimeError("cleanup_plan_state_changed")
    removed = []
    for image in reviewed["images"]:
        protected, _, hashes = protected_references(root)
        if hashes != reviewed["protectedManifests"] or image["id"] in protected \
                or set(image["references"]) & protected:
            raise RuntimeError("cleanup_protection_changed")
        # Removing the last tag can also remove its digest reference. Refresh each
        # reference instead of submitting already-removed digests in one command.
        for _ in range(len(image["references"]) + 1):
            current = next((value for value in inventory() if value["id"] == image["id"]), None)
            if current is None:
                break
            if not current["references"] or not set(current["references"]) <= set(image["references"]):
                raise RuntimeError("cleanup_image_references_changed")
            protected, _, hashes = protected_references(root)
            if hashes != reviewed["protectedManifests"] or current["id"] in protected \
                    or set(current["references"]) & protected:
                raise RuntimeError("cleanup_protection_changed")
            # No force; Docker also refuses an image newly used by a container.
            docker("image", "rm", current["references"][0])
        else:
            raise RuntimeError("cleanup_image_removal_incomplete")
        removed.append(image["id"])
    print(json.dumps({"status": "completed", "removed": removed}, sort_keys=True))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default="/opt/repoguard")
    parser.add_argument("--minimum-age-days", type=int, default=14, choices=range(7, 366))
    parser.add_argument("--maximum-images", type=int, default=20, choices=range(1, 21))
    parser.add_argument("--apply-plan", type=Path)
    parser.add_argument("--expected-sha256")
    arguments = parser.parse_args()
    requested = Path(arguments.root)
    if not requested.is_absolute() or requested.is_symlink() or not requested.is_dir():
        raise RuntimeError("unsafe_deployment_root")
    root = requested.resolve(strict=True)
    if str(root) in {"/", "/opt", "/var", "/home", "/root"} or root.stat().st_mode & 0o022:
        raise RuntimeError("unsafe_deployment_root")
    with deployment_lock(root):
        if arguments.apply_plan:
            apply(root, arguments.apply_plan, arguments.expected_sha256)
        else:
            if arguments.expected_sha256:
                raise RuntimeError("cleanup_plan_missing")
            print(json.dumps(plan(root, arguments.minimum_age_days, arguments.maximum_images), indent=2, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError, KeyError, OSError, subprocess.TimeoutExpired) as failure:
        print(json.dumps({"status": "failed", "reason": str(failure) if isinstance(failure, RuntimeError)
                          else "cleanup_precondition_failed"}), file=sys.stderr)
        sys.exit(2)
