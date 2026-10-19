#!/usr/bin/env python3
"""Forward the existing local Loki host-disk rules to an explicitly enabled DingTalk robot."""
import argparse
import base64
import fcntl
import hashlib
import hmac
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import time
import urllib.parse
import urllib.request

RULES = {
    "RepoGuardHostDiskWarning": ("warning", "磁盘空间预警"),
    "RepoGuardHostDiskCritical": ("critical", "磁盘空间紧急告警"),
    "RepoGuardHostDiskTelemetryMissing": ("warning", "磁盘采样中断"),
}
MAXIMUM_JSON = 1048576


def secure_path(path, private=False):
    info = path.lstat()
    if stat.S_ISLNK(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & (0o077 if private else 0o022):
        raise RuntimeError("unsafe_notification_path")


def read_json(path, private=False):
    secure_path(path, private)
    if not path.is_file() or path.stat().st_size > MAXIMUM_JSON:
        raise RuntimeError("unsafe_notification_file")
    return json.loads(path.read_bytes())


def write_json(path, value):
    if path.exists() or path.is_symlink():
        secure_path(path, True)
    descriptor, temporary = tempfile.mkstemp(prefix=".notify-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(value, stream, sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def settings(root):
    path = root / ".deploy-state/disk-notification.json"
    if not path.exists():
        return {"enabled": False}
    value = read_json(path, True)
    if set(value) != {"formatVersion", "enabled", "provider", "sourceBindingId", "webhookUrl", "signingSecret"} \
            or value["formatVersion"] != 1 or type(value["enabled"]) is not bool \
            or value["provider"] != "DINGTALK" or type(value["sourceBindingId"]) is not int \
            or value["sourceBindingId"] < 1 or not isinstance(value["webhookUrl"], str) \
            or not isinstance(value["signingSecret"], str):
        raise RuntimeError("invalid_notification_config")
    url = urllib.parse.urlsplit(value["webhookUrl"])
    query = urllib.parse.parse_qs(url.query, keep_blank_values=True)
    if value["enabled"] and (url.scheme != "https" or url.hostname != "oapi.dingtalk.com" \
            or url.port not in (None, 443) or url.path != "/robot/send" or url.username or url.password \
            or url.fragment or set(query) != {"access_token"} or len(query["access_token"]) != 1 \
            or not query["access_token"][0] or len(value["webhookUrl"]) > 4096 or len(value["signingSecret"]) > 1024):
        raise RuntimeError("invalid_dingtalk_endpoint")
    return value


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


def request_json(url, payload=None):
    # Never forward the robot credential through a redirect or inherited HTTP proxy.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    request = urllib.request.Request(url, data=None if payload is None else json.dumps(payload).encode(),
                                     headers={"Content-Type": "application/json"})
    with opener.open(request, timeout=10) as response:
        raw = response.read(MAXIMUM_JSON + 1)
    if len(raw) > MAXIMUM_JSON:
        raise RuntimeError("notification_response_too_large")
    return json.loads(raw)


def firing_alerts():
    result = request_json("http://127.0.0.1:3100/prometheus/api/v1/rules")
    if result.get("status") != "success":
        raise RuntimeError("loki_rules_not_ready")
    groups = [g for g in result.get("data", {}).get("groups", []) if g.get("name") == "repoguard-host-disk"]
    if len(groups) != 1 or len(groups[0].get("rules", [])) != 3:
        raise RuntimeError("disk_rules_missing")
    rules = groups[0]["rules"]
    if {r.get("name") for r in rules} != set(RULES) \
            or any(r.get("health") != "ok" or r.get("lastError") for r in rules):
        raise RuntimeError("disk_rules_unhealthy")
    active = {}
    for rule in rules:
        name = rule["name"]
        for alert in rule.get("alerts", []):
            if alert.get("state") != "firing":
                continue
            mount = str(alert.get("labels", {}).get("mount", "host"))[:256]
            key = name + ":" + mount
            active[key] = {"name": name, "mount": mount, "severity": RULES[name][0]}
    # A full filesystem also meets the warning threshold; send one critical entry.
    for value in list(active.values()):
        if value["name"] == "RepoGuardHostDiskCritical":
            active.pop("RepoGuardHostDiskWarning:" + value["mount"], None)
    return active


def signed_url(value, now):
    url = value["webhookUrl"]
    secret = value["signingSecret"]
    if not secret:
        return url
    timestamp = str(int(now * 1000))
    signature = base64.b64encode(hmac.new(secret.encode(), (timestamp + "\n" + secret).encode(), hashlib.sha256).digest()).decode()
    return url + "&" + urllib.parse.urlencode({"timestamp": timestamp, "sign": signature})


def message(active, previous, root, test=False):
    title = "RepoGuard 磁盘告警通道测试" if test else \
        "RepoGuard 磁盘告警恢复" if not active else \
        "RepoGuard 磁盘紧急告警" if any(x["severity"] == "critical" for x in active.values()) else "RepoGuard 磁盘预警"
    lines = ["### " + title]
    if test:
        lines.append("这是通道连通性测试，不代表磁盘发生故障。")
    for key in sorted(active):
        item = active[key]
        lines.append("- " + RULES[item["name"]][1] + "：" + item["mount"])
    for key in sorted(set(previous) - set(active)):
        item = previous[key]
        lines.append("- 已恢复：" + RULES[item["name"]][1] + "（" + item["mount"] + "）")
    usage = os.statvfs(root)
    available = usage.f_bavail * usage.f_frsize
    used = (usage.f_blocks - usage.f_bfree) * usage.f_frsize
    percent = 100 * used / (used + available) if used + available else 100
    lines += ["", "部署盘当前使用 %.1f%%，可用 %.2f GiB。" % (percent, available / 1024**3),
              "预警：80%或可用低于10 GiB持续10分钟；紧急：90%或低于5 GiB持续5分钟。"]
    return {"msgtype": "markdown", "markdown": {"title": title, "text": "\n".join(lines)}}


def notify(root, apply=False, test=False):
    config = settings(root)
    if not config["enabled"]:
        return {"status": "disabled", "sent": False}
    path = root / ".deploy-state/disk-notification-state.json"
    previous = read_json(path, True) if path.exists() else {"formatVersion": 1, "active": {}, "lastSentAt": 0}
    if previous.get("formatVersion") != 1 or not isinstance(previous.get("active"), dict) \
            or not isinstance(previous.get("lastSentAt"), (int, float)):
        raise RuntimeError("invalid_notification_state")
    old = previous["active"]
    if any(not isinstance(v, dict) or v.get("name") not in RULES or not isinstance(v.get("mount"), str) for v in old.values()):
        raise RuntimeError("invalid_notification_state")
    active = {} if test else firing_alerts()
    now = time.time()
    interval = 3600 if any(x["severity"] == "critical" for x in active.values()) else 14400
    changed = set(active) != set(old)
    due = bool(active) and now - previous["lastSentAt"] >= interval
    planned = test or changed or due
    if not apply:
        return {"status": "dry_run", "wouldSend": planned, "activeAlerts": len(active), "sourceBindingId": config["sourceBindingId"]}
    if not planned:
        return {"status": "idle", "sent": False, "activeAlerts": len(active)}
    response = request_json(signed_url(config, now), message(active, old, root, test))
    if type(response.get("errcode")) is not int or response["errcode"] != 0:
        raise RuntimeError("dingtalk_delivery_rejected")
    if not test:
        write_json(path, {"formatVersion": 1, "active": active, "lastSentAt": now})
    return {"status": "test_sent" if test else "sent", "sent": True, "activeAlerts": len(active)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("/opt/repoguard"))
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--test", action="store_true")
    args = parser.parse_args()
    root = args.root
    if not root.is_absolute() or root.resolve(strict=True) != root or str(root) in ("/", "/opt", "/var", "/home", "/root"):
        raise RuntimeError("unsafe_notification_root")
    secure_path(root)
    state = root / ".deploy-state"
    secure_path(state)
    descriptor = os.open(state / ".disk-notify.lock", os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        info = os.fstat(descriptor)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o077:
            raise RuntimeError("unsafe_notification_lock")
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print(json.dumps({"status": "busy", "sent": False}))
            return 0
        print(json.dumps(notify(root, args.apply, args.test), sort_keys=True))
        return 0
    finally:
        os.close(descriptor)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as failure:
        # HTTP exceptions contain the credential-bearing URL; never log them.
        safe_reason = str(failure) if isinstance(failure, RuntimeError) else "notification_operation_failed"
        print(json.dumps({"status": "failed", "reason": safe_reason}), file=sys.stderr)
        sys.exit(2)
