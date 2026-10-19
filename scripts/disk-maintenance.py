#!/usr/bin/env python3
"""Bounded host capacity checks and opt-in RepoGuard image retention."""
import argparse
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
import fcntl
import hashlib
import importlib.util
import json
import logging
from logging.handlers import RotatingFileHandler
import math
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile

GIB = 1024**3
IDENTITY = re.compile(r"sha256:[0-9a-f]{64}\Z")
REVISION = re.compile(r"[0-9a-f]{40}\Z")
POLICY_KEYS = {"formatVersion", "enabled", "minimumAgeDays", "maximumImages",
               "pinnedRevisions", "pruneBuildCache"}


def load_module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


pruner = load_module("image_retention", "prune-prod-images.py")
release = load_module("release_identity", "release-manifest.py")


def read_json(path, maximum=262144):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o022 or info.st_size > maximum:
        raise RuntimeError("unsafe_maintenance_file")
    return json.loads(path.read_bytes())


def write_json(path, value):
    raw = (json.dumps(value, sort_keys=True, indent=2) + "\n").encode()
    fd, temporary = tempfile.mkstemp(prefix=".disk-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def policy(root):
    path = root / ".deploy-state/disk-maintenance-policy.json"
    if not path.exists():
        return {"formatVersion": 1, "enabled": False, "minimumAgeDays": 7,
                "maximumImages": 20, "pinnedRevisions": [], "pruneBuildCache": False}
    value = read_json(path)
    if set(value) != POLICY_KEYS or type(value["formatVersion"]) is not int or value["formatVersion"] != 1 \
            or type(value["enabled"]) is not bool or type(value["pruneBuildCache"]) is not bool \
            or type(value["minimumAgeDays"]) is not int or not 7 <= value["minimumAgeDays"] <= 365 \
            or type(value["maximumImages"]) is not int or not 1 <= value["maximumImages"] <= 20 \
            or not isinstance(value["pinnedRevisions"], list) or len(value["pinnedRevisions"]) > 32 \
            or not all(isinstance(x, str) and REVISION.fullmatch(x) for x in value["pinnedRevisions"]):
        raise RuntimeError("invalid_disk_policy")
    return value


def history(root):
    path = root / ".deploy-state/disk-successful-releases.json"
    if not path.exists():
        return []
    value = read_json(path)
    if value.get("formatVersion") != 1 or not isinstance(value.get("releases"), list) or len(value["releases"]) > 3:
        raise RuntimeError("invalid_disk_release_history")
    entries = value["releases"]
    if len({x["version"] for x in entries}) != len(entries):
        raise RuntimeError("duplicate_disk_release_history")
    for entry in entries:
        release.validate(entry["manifest"], True)
        if entry["version"] != entry["manifest"]["version"]:
            raise RuntimeError("invalid_disk_release_history")
    return entries


@contextmanager
def locked(root, inherited=False):
    if inherited:
        # The deployment shell keeps descriptor 9 open across this child process.
        info = os.fstat(9)
        target = (root / ".deploy.lock").lstat()
        if not stat.S_ISREG(info.st_mode) or (info.st_dev, info.st_ino) != (target.st_dev, target.st_ino):
            raise RuntimeError("deployment_lock_not_inherited")
        fcntl.flock(9, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield
    else:
        with pruner.deployment_lock(root):
            yield


def containers():
    ids = pruner.docker("ps", "-aq").split()
    if not ids:
        raise RuntimeError("production_containers_missing")
    return json.loads(pruner.docker("container", "inspect", *ids))


def healthy(values):
    by_name = {x["Name"].lstrip("/"): x for x in values}
    for name in ("repoguard-backend", "repoguard-frontend", "repoguard-caddy"):
        item = by_name.get(name, {})
        state = item.get("State", {})
        if state.get("Status") != "running" or state.get("Health", {}).get("Status") != "healthy":
            raise RuntimeError("production_health_not_ready")


def record(root, manifest_path):
    state = root / ".deploy-state"
    path = manifest_path.resolve(strict=True)
    if manifest_path.is_symlink() or path.parent != state:
        raise RuntimeError("release_manifest_outside_state")
    manifest = release.validate(read_json(path), True)
    observed = containers()
    healthy(observed)
    by_name = {x["Name"].lstrip("/"): x for x in observed}
    for role in ("backend", "frontend"):
        item = manifest["images"][role]
        container = by_name["repoguard-" + role]
        immutable = item["target"]["repository"] + "@" + item["target"]["digest"]
        if container["Image"] not in (item["imageId"], item["target"]["digest"]) \
                or container["Config"]["Image"] != immutable:
            raise RuntimeError("successful_release_identity_mismatch")
    worker = by_name.get("repoguard-backend-worker")
    if worker and (worker["Image"] != by_name["repoguard-backend"]["Image"]
                   or worker["State"]["Status"] != "running"
                   or worker["State"].get("Health", {}).get("Status") != "healthy"):
        raise RuntimeError("successful_worker_identity_mismatch")
    entries = [x for x in history(root) if x["version"] != manifest["version"]]
    entries.insert(0, {"version": manifest["version"], "manifest": manifest})
    write_json(state / "disk-successful-releases.json", {"formatVersion": 1, "releases": entries[:3]})
    return {"status": "release_recorded", "retainedVersions": [x["version"] for x in entries[:3]]}


def capacity_sample(root):
    # Include Docker's image store, containerd and transfer temp space if separate mounts are used.
    paths = [root, Path("/tmp"), Path("/var/lib/docker"), Path("/var/lib/containerd")]
    filesystems = []
    devices = set()
    for path in paths:
        if not path.exists():
            continue
        device = path.stat().st_dev
        if device in devices:
            continue
        devices.add(device)
        usage = os.statvfs(path)
        available = usage.f_bavail * usage.f_frsize
        used = (usage.f_blocks - usage.f_bfree) * usage.f_frsize
        percent = math.ceil(100 * used / (used + available)) if used + available else 100
        severity = "critical" if percent >= 90 or available < 5 * GIB else \
            "warning" if percent >= 80 or available < 10 * GIB else "normal"
        filesystems.append({"mount": str(path), "availableBytes": available,
                            "usedPercent": percent, "severity": severity})
    return {"timestamp": datetime.now(timezone.utc).isoformat(), "event": "host_disk_capacity",
            "filesystems": filesystems, "minimumAvailableBytes": min(x["availableBytes"] for x in filesystems)}


def publish(root, event):
    directory = root / "disk-status"
    if directory.is_symlink() or not directory.is_dir() or directory.stat().st_uid != os.geteuid() \
            or directory.stat().st_mode & 0o022:
        raise RuntimeError("unsafe_disk_status_directory")
    path = directory / "capacity.jsonl"
    if path.is_symlink() or (path.exists() and (not path.is_file() or path.stat().st_uid != os.geteuid()
                                               or path.stat().st_mode & 0o022)):
        raise RuntimeError("unsafe_disk_status_file")
    handler = RotatingFileHandler(path, maxBytes=1048576, backupCount=2, encoding="utf-8")
    try:
        for item in event["filesystems"]:
            record = logging.LogRecord("disk", logging.INFO, "", 0,
                                       json.dumps({"event": event["event"], "timestamp": event["timestamp"], **item}), (), None)
            handler.emit(record)
        path.chmod(0o644)
    finally:
        handler.close()


def retention_plan(root):
    settings = policy(root)
    entries = history(root)
    observed = containers()
    healthy(observed)
    protected = {x["Image"] for x in observed} | {x["Config"]["Image"] for x in observed}
    # A capacity-triggered cleanup must also preserve an explicitly requested rollback candidate.
    protected.update(os.environ[key] for key in ("BACKEND_IMAGE", "FRONTEND_IMAGE") if os.environ.get(key))
    repositories = set()
    for entry in entries:
        for image in entry["manifest"]["images"].values():
            protected.add(image["imageId"])
            for origin in ("source", "target"):
                item = image[origin]
                repo = item["repository"]
                if repo not in {"ghcr.io/cocojiu/pragent-backend", "ghcr.io/cocojiu/pragent-frontend"} \
                        and not re.fullmatch(r"[a-z0-9.-]+\.aliyuncs\.com/cocojiu/pragent", repo):
                    raise RuntimeError("unexpected_project_repository")
                repositories.add(repo)
                protected.update((item["digest"], repo + "@" + item["digest"]))
    images = pruner.inventory()
    cutoff = datetime.now(timezone.utc) - timedelta(days=settings["minimumAgeDays"])
    candidates = [x for x in images if pruner.eligible(x, protected, repositories,
                  settings["pinnedRevisions"], cutoff)] if len(entries) == 3 else []
    candidates.sort(key=lambda x: (pruner.timestamp(x["created"]), x["id"]))
    return {"status": "ready" if len(entries) == 3 else "waiting_for_three_successful_releases",
            "enabled": settings["enabled"], "retainedVersions": [x["version"] for x in entries],
            "historySha256": hashlib.sha256(json.dumps(entries, sort_keys=True).encode()).hexdigest(),
            "pinnedRevisions": settings["pinnedRevisions"], "protectedReferences": sorted(protected),
            "eligibleImages": len(candidates), "images": candidates[:settings["maximumImages"]],
            "policy": settings}


def recoverable(image):
    references = [ref for ref in image["references"] if "@sha256:" in ref]
    if not references:
        return False
    def inspect(reference):
        result = subprocess.run(["docker", "manifest", "inspect", reference],
                                capture_output=True, text=True, timeout=90)
        if result.returncode:
            return {}
        try:
            value = json.loads(result.stdout)
            return value if isinstance(value, dict) and value.get("schemaVersion") == 2 else {}
        except ValueError:
            return {}
    reference = references[0]
    remote = inspect(reference)
    if remote.get("config", {}).get("digest") == image["id"]:
        return True
    # An index digest alone does not prove its linux/amd64 config is the local image.
    variants = remote.get("manifests", [])
    if not isinstance(variants, list) or len(variants) > 32:
        return False
    for variant in variants:
        if variant.get("platform", {}).get("os") == "linux" \
                and variant.get("platform", {}).get("architecture") == "amd64" \
                and IDENTITY.fullmatch(variant.get("digest", "")):
            child = inspect(pruner.repository(reference) + "@" + variant["digest"])
            if child.get("config", {}).get("digest") == image["id"]:
                return True
    return False


def perform_cleanup(root, apply=False):
    planned = retention_plan(root)
    if not apply or not planned["enabled"]:
        return {**planned, "mode": "dry_run" if not apply else "disabled", "removed": []}
    removed, unavailable = [], []
    def checkpoint(status):
        write_json(root / ".deploy-state/disk-last-cleanup.json", {
            "status": status, "removed": removed, "registryUnavailable": unavailable,
            "retainedVersions": planned["retainedVersions"],
            "candidateIds": [x["id"] for x in planned["images"]]})
    checkpoint("running")
    for image in planned["images"]:
        fresh = retention_plan(root)
        if fresh["policy"] != planned["policy"] or fresh["historySha256"] != planned["historySha256"]:
            raise RuntimeError("retention_policy_changed")
        if not any(x["id"] == image["id"] and x["references"] == image["references"] for x in fresh["images"]):
            raise RuntimeError("retention_candidate_changed")
        # Unrecoverable or tag-only local images require the manual reviewed-plan procedure.
        if not recoverable(image):
            unavailable.append(image["id"])
            continue
        for _ in range(len(image["references"]) + 1):
            current = pruner.current_image(image["id"])
            if current is None:
                break
            if not current["references"] or not set(current["references"]) <= set(image["references"]):
                raise RuntimeError("retention_image_references_changed")
            history_hash = hashlib.sha256(json.dumps(history(root), sort_keys=True).encode()).hexdigest()
            if policy(root) != planned["policy"] or history_hash != planned["historySha256"]:
                raise RuntimeError("retention_policy_changed")
            # Recheck container protection immediately before each non-forced Docker deletion.
            if any(c["Image"] == image["id"] or c["Config"]["Image"] in current["references"] for c in containers()):
                raise RuntimeError("retention_container_reference_changed")
            pruner.docker("image", "rm", "--no-prune", current["references"][0])
        else:
            raise RuntimeError("retention_removal_incomplete")
        removed.append(image["id"])
        checkpoint("running")
    cache = "disabled"
    if planned["policy"]["pruneBuildCache"]:
        cache = pruner.docker("builder", "prune", "--all", "--force", "--filter", "until=168h", "--keep-storage", "1GB")
    result = {"status": "completed", "removed": removed, "registryUnavailable": unavailable,
              "cache": cache.strip(), "capacity": capacity_sample(root)}
    write_json(root / ".deploy-state/disk-last-cleanup.json", result)
    return result


def cleanup(root, apply=False):
    try:
        return perform_cleanup(root, apply)
    except Exception:
        # Preserve the last per-image checkpoint if a later operation fails.
        if apply:
            try:
                path = root / ".deploy-state/disk-last-cleanup.json"
                previous = read_json(path) if path.exists() else {}
                write_json(path, {**previous, "status": "failed"})
            except Exception:
                pass
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("/opt/repoguard"))
    parser.add_argument("--deployment-lock-held", action="store_true")
    commands = parser.add_subparsers(dest="command", required=True)
    check = commands.add_parser("check")
    check.add_argument("--publish", action="store_true")
    commands.add_parser("capacity")
    commands.add_parser("plan")
    clean = commands.add_parser("cleanup")
    clean.add_argument("--apply", action="store_true")
    recorded = commands.add_parser("record-release")
    recorded.add_argument("--manifest", type=Path, required=True)
    args = parser.parse_args()
    root = args.root
    if not root.is_absolute() or root.is_symlink() or root.resolve(strict=True) != root or not root.is_dir() \
            or str(root) in ("/", "/opt", "/var", "/home", "/root") \
            or root.stat().st_uid != os.geteuid() or root.stat().st_mode & 0o022:
        raise RuntimeError("unsafe_deployment_root")
    state = root / ".deploy-state"
    if state.exists() and (not state.is_dir() or state.is_symlink() or state.stat().st_uid != os.geteuid()
                           or state.stat().st_mode & 0o022):
        raise RuntimeError("unsafe_deployment_state")
    if not state.exists() and args.command not in ("capacity", "check"):
        raise RuntimeError("deployment_state_missing")
    if args.command == "check":
        result = capacity_sample(root)
        if args.publish:
            publish(root, result)
    else:
        with locked(root, args.deployment_lock_held):
            if args.command == "record-release":
                result = record(root, args.manifest)
            elif args.command == "plan":
                result = cleanup(root)
            elif args.command == "cleanup":
                result = cleanup(root, args.apply)
            else:
                result = capacity_sample(root)
                if result["minimumAvailableBytes"] < 8 * GIB and policy(root)["enabled"]:
                    cleanup(root, apply=True)
                    result = capacity_sample(root)
                if result["minimumAvailableBytes"] < 8 * GIB:
                    print(json.dumps({"status": "insufficient_capacity", **result}, sort_keys=True))
                    return 2
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (RuntimeError, ValueError, KeyError, TypeError, OSError, subprocess.TimeoutExpired) as failure:
        print(json.dumps({"status": "failed", "reason": str(failure) if isinstance(failure, RuntimeError)
                          else "disk_maintenance_precondition_failed"}), file=sys.stderr)
        sys.exit(2)
