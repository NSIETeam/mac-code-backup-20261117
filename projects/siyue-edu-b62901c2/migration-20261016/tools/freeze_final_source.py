#!/usr/bin/env python3
"""Create a same-server, approved final CRM source freeze. Default operation is read-only."""

from __future__ import annotations

import argparse
import hashlib
import hmac
import importlib.util
import json
import os
import re
import shutil
import sqlite3
import stat
import subprocess
import sys
import tempfile
import urllib.parse
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path("/var/lib/siyue-crm-auth")
BUSINESS = ROOT / "business.sqlite"
AUTH = ROOT / "auth.json"
FREEZE_ROOT = Path("/var/lib/siyue-crm-freezes")
APPROVAL_ROOT = Path("/etc/siyue-crm-freeze/approved")
UNITS = ("siyue-crm-backup.timer", "siyue-crm-backup.service", "siyue-crm-auth.service")
SYSTEMD_PROPERTIES = ("LoadState", "ActiveState", "SubState", "MainPID")
TIMER_PROPERTIES = ("LoadState", "ActiveState", "SubState")
MAINTENANCE_URLS = (
    "https://www.siyuejn.com/crm-api",
    "https://www.siyuejn.com/crm-api/",
    "https://www.siyuejn.com/crm-api/v1/",
    "https://www.siyuejn.com/crm-api/v2/",
    "https://siyuejn.com/crm-api",
    "https://siyuejn.com/crm-api/",
    "https://siyuejn.com/crm-api/v1/",
    "https://siyuejn.com/crm-api/v2/",
)
STATIC_URLS = ("https://www.siyuejn.com/crm/", "https://siyuejn.com/crm/")
EXPORTER = Path(__file__).with_name("export_source.py")
FEEDBACK = Path(__file__).with_name("merge-auth-feedback.py")
MANIFEST_SCHEMA = "siyue.final-source-freeze-approval/v1"
HEX64 = re.compile(r"^[0-9a-f]{64}$")
FREEZE_ID = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9._-]{7,63}$")


class FreezeError(Exception):
    def __init__(self, stage: str, category: str):
        super().__init__(f"{stage}:{category}")
        self.stage = stage
        self.category = category


@dataclass(frozen=True)
class Paths:
    business: Path = BUSINESS
    auth: Path = AUTH
    freeze_root: Path = FREEZE_ROOT
    approval_root: Path = APPROVAL_ROOT
    exporter: Path = EXPORTER
    feedback: Path = FEEDBACK
    reward_key: Path = Path("/opt/siyue-java-real-candidate/v101/source-input/reward-key.env")
    owner_uid: int = 0


class SystemOps:
    """Small OS boundary so synthetic tests can prove ordering without touching production."""

    def run(self, argv: list[str], *, timeout: int = 20, capture: bool = True) -> subprocess.CompletedProcess:
        try:
            return subprocess.run(argv, check=False, timeout=timeout, text=True,
                                  env={"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LANG": "C"},
                                  stdout=subprocess.PIPE if capture else subprocess.DEVNULL,
                                  stderr=subprocess.PIPE if capture else subprocess.DEVNULL)
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise FreezeError("PREFLIGHT", type(exc).__name__) from None

    def unit_active(self, unit: str) -> bool:
        if unit not in UNITS:
            raise FreezeError("PREFLIGHT", "SYSTEMD_UNIT_NOT_ALLOWED")
        is_timer = unit.endswith(".timer")
        properties = TIMER_PROPERTIES if is_timer else SYSTEMD_PROPERTIES
        command = ["/usr/bin/systemctl", "show", "--no-pager"]
        command.extend(f"--property={name}" for name in properties)
        result = self.run(command + [unit])
        if result.returncode != 0:
            raise FreezeError("PREFLIGHT", "SYSTEMD_QUERY_FAILED")
        values = {}
        for line in result.stdout.splitlines():
            if "=" in line:
                key, value = line.split("=", 1)
                values[key] = value
        if set(values) != set(properties) or values["LoadState"] != "loaded":
            raise FreezeError("PREFLIGHT", "SYSTEMD_UNIT_NOT_LOADED")
        active_state = values["ActiveState"]
        sub_state = values["SubState"]
        if is_timer:
            if (active_state, sub_state) == ("active", "waiting"):
                return True
            if (active_state, sub_state) in {("inactive", "dead"), ("failed", "failed")}:
                return False
            raise FreezeError("PREFLIGHT", "SYSTEMD_STATE_UNSETTLED")
        try:
            pid = int(values["MainPID"])
        except ValueError:
            raise FreezeError("PREFLIGHT", "SYSTEMD_STATE_INVALID") from None
        if active_state == "active" and sub_state == "running" and pid > 0:
            return True
        # The backup service is Type=oneshot; systemd reports its completed active
        # state as active/exited with no MainPID until it is explicitly stopped.
        if unit == "siyue-crm-backup.service" and (active_state, sub_state, pid) == ("active", "exited", 0):
            return True
        if (active_state, sub_state, pid) in {
                ("inactive", "dead", 0), ("failed", "failed", 0)}:
            return False
        raise FreezeError("PREFLIGHT", "SYSTEMD_STATE_UNSETTLED")

    def stop(self, unit: str) -> None:
        result = self.run(["/usr/bin/systemctl", "stop", unit], timeout=60)
        if result.returncode:
            raise FreezeError("STOP_WRITERS", "SYSTEMD_STOP_FAILED")

    def local_http_probe(self, url: str) -> tuple[int, dict[str, str]]:
        host = urllib.parse.urlsplit(url).hostname
        if host not in {"www.siyuejn.com", "siyuejn.com"} or not url.startswith("https://"):
            raise FreezeError("MAINTENANCE", "HTTP_TARGET_NOT_ALLOWED")
        result = self.run(["/usr/bin/curl", "--silent", "--show-error", "--output", "/dev/null",
                           "--write-out", "%{http_code}\\n%header{cache-control}\\n%header{retry-after}\\n",
                           "--resolve", f"{host}:443:127.0.0.1",
                           "--max-time", "8", url])
        if result.returncode:
            raise FreezeError("MAINTENANCE", "HTTP_PROBE_FAILED")
        try:
            status, cache_control, retry_after = result.stdout.rstrip().split("\n", 2)
            return int(status), {"cache-control": cache_control.strip().lower(),
                                 "retry-after": retry_after.strip()}
        except ValueError:
            raise FreezeError("MAINTENANCE", "HTTP_PROBE_INVALID") from None

    def open_file_pids(self, path: Path) -> list[int]:
        result = self.run(["/usr/bin/lsof", "-t", "--", str(path)])
        if result.returncode not in (0, 1):
            raise FreezeError("STOP_WRITERS", "OPEN_FILE_QUERY_FAILED")
        try:
            return [int(line) for line in result.stdout.splitlines() if line.strip()]
        except ValueError:
            raise FreezeError("STOP_WRITERS", "OPEN_FILE_QUERY_INVALID") from None


def _sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _safe_json(path: Path, *, expected_uid: int, private: bool = True) -> tuple[bytes, dict]:
    try:
        if not path.is_absolute() or path.is_symlink() or not path.is_file():
            raise ValueError
        _no_symlink_components(path)
        st = path.stat()
        if st.st_uid != expected_uid or (private and stat.S_IMODE(st.st_mode) != 0o600):
            raise ValueError
        raw = path.read_bytes()
        parsed = json.loads(raw, object_pairs_hook=_unique_object)
        if not isinstance(parsed, dict):
            raise ValueError
        return raw, parsed
    except (OSError, ValueError, json.JSONDecodeError):
        raise FreezeError("APPROVAL", "MANIFEST_INVALID") from None


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate key")
        result[key] = value
    return result


def _no_symlink_components(path: Path) -> None:
    for part in (path, *path.parents):
        if part.is_symlink():
            raise ValueError("symlink path")


def _private_directory(path: Path, owner_uid: int, mode: int = 0o700) -> None:
    if path.is_symlink() or not path.is_dir():
        raise FreezeError("PATH_POLICY", "PRIVATE_DIRECTORY_INVALID")
    st = path.stat(follow_symlinks=False)
    if st.st_uid != owner_uid or stat.S_IMODE(st.st_mode) != mode:
        raise FreezeError("PATH_POLICY", "PRIVATE_DIRECTORY_POLICY")
    for parent in path.parents:
        if parent.is_symlink():
            raise FreezeError("PATH_POLICY", "SYMLINK_PARENT")
        parent_stat = parent.stat(follow_symlinks=False)
        parent_mode = stat.S_IMODE(parent_stat.st_mode)
        if parent_mode & 0o022 and not parent_mode & stat.S_ISVTX:
            raise FreezeError("PATH_POLICY", "WRITABLE_PARENT")


def _approval(path: Path, paths: Paths, expected_digest: str | None, execute: bool) -> tuple[dict, str]:
    if path.parent != paths.approval_root or path.name != "approved.json":
        raise FreezeError("APPROVAL", "MANIFEST_PATH_NOT_ALLOWED")
    parent = path.parent
    while parent != parent.parent:
        if parent.is_symlink():
            raise FreezeError("APPROVAL", "MANIFEST_PATH_NOT_ALLOWED")
        parent = parent.parent
    _private_directory(paths.approval_root, paths.owner_uid)
    raw, manifest = _safe_json(path, expected_uid=paths.owner_uid)
    digest = _sha(raw)
    if execute and (not expected_digest or not HEX64.fullmatch(expected_digest) or digest != expected_digest):
        raise FreezeError("APPROVAL", "APPROVAL_DIGEST_MISMATCH")
    required = {"schema", "approved", "freezeId", "businessRevision", "businessStateSha256",
                "authSha256", "businessUid", "authUid", "rewardKeyPath", "maintenanceVerifiedAt"}
    if set(manifest) != required or manifest.get("schema") != MANIFEST_SCHEMA or manifest.get("approved") is not True:
        raise FreezeError("APPROVAL", "MANIFEST_SCHEMA_INVALID")
    if not FREEZE_ID.fullmatch(str(manifest["freezeId"])):
        raise FreezeError("APPROVAL", "FREEZE_ID_INVALID")
    for key in ("businessStateSha256", "authSha256"):
        if not isinstance(manifest.get(key), str) or not HEX64.fullmatch(manifest[key]):
            raise FreezeError("APPROVAL", "MANIFEST_HASH_INVALID")
    if not isinstance(manifest.get("businessRevision"), int) or manifest["businessRevision"] < 0:
        raise FreezeError("APPROVAL", "MANIFEST_REVISION_INVALID")
    if not all(isinstance(manifest.get(key), int) and manifest[key] >= 0 for key in ("businessUid", "authUid")):
        raise FreezeError("APPROVAL", "MANIFEST_OWNER_INVALID")
    if manifest["rewardKeyPath"] != str(paths.reward_key):
        raise FreezeError("APPROVAL", "REWARD_KEY_PATH_NOT_ALLOWED")
    return manifest, digest


def _file_identity(path: Path) -> dict[str, int]:
    try:
        _no_symlink_components(path)
        st = path.stat(follow_symlinks=False)
        if not stat.S_ISREG(st.st_mode):
            raise ValueError
        return {"uid": st.st_uid, "mode": stat.S_IMODE(st.st_mode), "device": st.st_dev,
                "inode": st.st_ino, "size": st.st_size}
    except (OSError, ValueError):
        raise FreezeError("SOURCE_VALIDATE", "SOURCE_FILE_INVALID") from None


def _read_auth(path: Path, expected_uid: int) -> bytes:
    identity = _file_identity(path)
    if identity["uid"] != expected_uid or identity["mode"] != 0o600 or identity["size"] > 64 * 1024 * 1024:
        raise FreezeError("SOURCE_VALIDATE", "AUTH_FILE_POLICY")
    return path.read_bytes()


def _export_business(path: Path, exporter: Path) -> tuple[bytes, dict]:
    try:
        result = subprocess.run([sys.executable, str(exporter), "--sqlite", str(path)], check=False,
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=120,
                                env={"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LANG": "C"})
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise FreezeError("SOURCE_VALIDATE", type(exc).__name__) from None
    if result.returncode != 0:
        raise FreezeError("SOURCE_VALIDATE", "BUSINESS_EXPORT_FAILED")
    try:
        envelope = json.loads(result.stdout)
        if envelope.get("schema") != "siyue.legacy-business/v1" or not isinstance(envelope.get("state"), dict):
            raise ValueError
        return result.stdout, envelope
    except (json.JSONDecodeError, ValueError, AttributeError):
        raise FreezeError("SOURCE_VALIDATE", "BUSINESS_EXPORT_INVALID") from None


def _reward_available(business_path: Path, key_path: str, reward_count: int, expected_uid: int = 0) -> bool:
    if reward_count == 0:
        return True
    try:
        key_file = Path(key_path)
        if key_file.is_symlink() or not key_file.is_file():
            return False
        key_stat = key_file.stat()
        if key_stat.st_uid != expected_uid or stat.S_IMODE(key_stat.st_mode) != 0o600:
            return False
        raw = key_file.read_text(encoding="utf-8")
        match = re.search(r"(?m)^SIYUE_LEGACY_REWARD_KEY_HEX=([0-9a-fA-F]{64})\s*$", raw)
        if not match:
            return False
        with sqlite3.connect(f"file:{business_path}?mode=ro", uri=True) as db:
            db.execute("PRAGMA query_only=ON")
            row = db.execute("SELECT value FROM business_meta WHERE key='visitor-secret'").fetchone()
        return (row is not None and isinstance(row[0], str) and HEX64.fullmatch(row[0]) is not None
                and hmac.compare_digest(row[0].lower(), match.group(1).lower()))
    except (OSError, sqlite3.Error, UnicodeError):
        return False


def _count(values: object) -> int:
    return len(values) if isinstance(values, list) else 0


def _check_snapshot(manifest: dict, paths: Paths) -> tuple[bytes, bytes, dict, dict]:
    business_before = _file_identity(paths.business)
    auth_before = _file_identity(paths.auth)
    _private_directory(paths.business.parent, manifest["businessUid"])
    _private_directory(paths.auth.parent, manifest["authUid"])
    if business_before["uid"] != manifest["businessUid"] or business_before["mode"] != 0o600:
        raise FreezeError("SOURCE_VALIDATE", "BUSINESS_FILE_POLICY")
    auth_bytes = _read_auth(paths.auth, manifest["authUid"])
    business_bytes, envelope = _export_business(paths.business, paths.exporter)
    if envelope.get("revision") != manifest["businessRevision"] or envelope.get("sourceSha256") != manifest["businessStateSha256"]:
        raise FreezeError("SOURCE_VALIDATE", "BUSINESS_SOURCE_CHANGED")
    if _sha(auth_bytes) != manifest["authSha256"]:
        raise FreezeError("SOURCE_VALIDATE", "AUTH_SOURCE_CHANGED")
    if not envelope.get("auditProvenance"):
        raise FreezeError("SOURCE_VALIDATE", "AUDIT_PROVENANCE_MISSING")
    rewards = _count(envelope["state"].get("rewardClaims"))
    if not isinstance(envelope["state"].get("rewardClaims"), list):
        raise FreezeError("SOURCE_VALIDATE", "REWARD_COLLECTION_INVALID")
    key_available = _reward_available(paths.business, manifest["rewardKeyPath"], rewards, paths.owner_uid)
    if not key_available:
        raise FreezeError("SOURCE_VALIDATE", "REWARD_KEY_UNAVAILABLE")
    return business_bytes, auth_bytes, envelope, {"business": business_before, "auth": auth_before,
                                                   "rewardClaims": rewards}


class FreezeRunner:
    def __init__(self, paths: Paths = Paths(), ops: SystemOps | None = None, uid: int | None = None):
        self.paths = paths
        self.ops = ops or SystemOps()
        self.uid = os.geteuid() if uid is None else uid

    def _maintenance(self) -> None:
        responses = [self.ops.local_http_probe(url) for url in MAINTENANCE_URLS]
        static_responses = [self.ops.local_http_probe(url) for url in STATIC_URLS]
        if any(status != 503 or headers != {"cache-control": "no-store", "retry-after": "60"}
               for status, headers in responses) or any(status != 200 for status, _ in static_responses):
            raise FreezeError("MAINTENANCE", "CRM_BOUNDARY_NOT_CONFIRMED")

    def plan(self, manifest_path: Path) -> dict:
        manifest, digest = _approval(manifest_path, self.paths, None, False)
        self._maintenance()
        for unit in UNITS:
            self.ops.unit_active(unit)
        business, auth, envelope, identity = _check_snapshot(manifest, self.paths)
        self._validate_feedback(business, auth)
        return {"mode": "plan", "freezeId": manifest["freezeId"], "approvalSha256": digest,
                "revision": envelope["revision"], "businessStateSha256": envelope["sourceSha256"],
                "authSha256": _sha(auth), "auditEvents": len(envelope["auditProvenance"]),
                "rewardClaims": identity["rewardClaims"], "rewardKeyAvailable": True,
                "sourceUids": {"business": identity["business"]["uid"], "auth": identity["auth"]["uid"]},
                "wouldStop": list(UNITS), "wouldWrite": str(self.paths.freeze_root / manifest["freezeId"]),
                "readOnly": True}

    def prepare(self) -> dict:
        _private_directory(self.paths.business.parent, _file_identity(self.paths.business)["uid"])
        _private_directory(self.paths.auth.parent, _file_identity(self.paths.auth)["uid"])
        business_identity = _file_identity(self.paths.business)
        auth_identity = _file_identity(self.paths.auth)
        if business_identity["mode"] != 0o600 or auth_identity["mode"] != 0o600:
            raise FreezeError("SOURCE_VALIDATE", "SOURCE_FILE_POLICY")
        auth_bytes = _read_auth(self.paths.auth, auth_identity["uid"])
        _, envelope = _export_business(self.paths.business, self.paths.exporter)
        if not envelope.get("auditProvenance"):
            raise FreezeError("SOURCE_VALIDATE", "AUDIT_PROVENANCE_MISSING")
        reward_count = _count(envelope["state"].get("rewardClaims"))
        if not _reward_available(self.paths.business, str(self.paths.reward_key), reward_count, self.paths.owner_uid):
            raise FreezeError("SOURCE_VALIDATE", "REWARD_KEY_UNAVAILABLE")
        return {"mode": "prepare", "revision": envelope["revision"],
                "businessStateSha256": envelope["sourceSha256"], "authSha256": _sha(auth_bytes),
                "businessUid": business_identity["uid"], "authUid": auth_identity["uid"],
                "auditEvents": len(envelope["auditProvenance"]), "rewardClaims": reward_count,
                "rewardKeyAvailable": True, "readOnly": True}

    def execute(self, manifest_path: Path, approved_digest: str) -> dict:
        if self.uid != 0:
            raise FreezeError("APPROVAL", "ROOT_REQUIRED")
        manifest, digest = _approval(manifest_path, self.paths, approved_digest, True)
        _private_directory(self.paths.approval_root, self.paths.owner_uid)
        self._maintenance()
        before_business = _file_identity(self.paths.business)
        before_auth = _file_identity(self.paths.auth)
        if before_business["uid"] != manifest["businessUid"] or before_auth["uid"] != manifest["authUid"]:
            raise FreezeError("SOURCE_VALIDATE", "SOURCE_OWNER_MISMATCH")
        # Reject stale approvals before stopping anything, then repeat the same checks after drain.
        preflight = _check_snapshot(manifest, self.paths)
        self._validate_feedback(preflight[0], preflight[1])
        if self.paths.freeze_root.exists() and self.paths.freeze_root.is_symlink():
            raise FreezeError("OUTPUT", "FREEZE_ROOT_SYMLINK")
        try:
            _no_symlink_components(self.paths.freeze_root)
        except ValueError:
            raise FreezeError("OUTPUT", "FREEZE_ROOT_SYMLINK") from None
        destination = self.paths.freeze_root / manifest["freezeId"]
        if destination.exists():
            raise FreezeError("OUTPUT", "FREEZE_ALREADY_EXISTS")
        for unit in UNITS:
            if self.ops.unit_active(unit):
                self.ops.stop(unit)
        if any(self.ops.unit_active(unit) for unit in UNITS):
            raise FreezeError("STOP_WRITERS", "WRITER_UNIT_STILL_ACTIVE")
        after_business = _file_identity(self.paths.business)
        after_auth = _file_identity(self.paths.auth)
        for before, after in ((before_business, after_business), (before_auth, after_auth)):
            if any(before[key] != after[key] for key in ("uid", "device", "inode")):
                raise FreezeError("STOP_WRITERS", "SOURCE_IDENTITY_CHANGED")
        related_business = [self.paths.business, Path(str(self.paths.business) + "-wal"),
                            Path(str(self.paths.business) + "-shm"), Path(str(self.paths.business) + "-journal")]
        if any(self.ops.open_file_pids(path) for path in related_business + [self.paths.auth]):
            raise FreezeError("STOP_WRITERS", "SOURCE_STILL_OPEN")
        lock = None
        try:
            # BEGIN IMMEDIATE reserves SQLite's writer slot while the final envelope is read.
            # This closes the race between the process-handle check and the read snapshot.
            lock = sqlite3.connect(self.paths.business, timeout=10, isolation_level=None)
            lock.execute("BEGIN IMMEDIATE")
            business_bytes, auth_bytes, envelope, identity = _check_snapshot(manifest, self.paths)
            self._validate_feedback(business_bytes, auth_bytes)
            after_business = _file_identity(self.paths.business)
            after_auth = _file_identity(self.paths.auth)
            if (after_business["inode"] != before_business["inode"]
                    or after_business["uid"] != manifest["businessUid"]
                    or after_business["mode"] != 0o600):
                raise FreezeError("STOP_WRITERS", "BUSINESS_SOURCE_REPLACED")
            if (after_auth["inode"] != before_auth["inode"] or after_auth["uid"] != manifest["authUid"]
                    or after_auth["mode"] != 0o600 or _sha(self.paths.auth.read_bytes()) != manifest["authSha256"]):
                raise FreezeError("STOP_WRITERS", "AUTH_SOURCE_CHANGED")
            self._create_bundle(destination, business_bytes, auth_bytes, envelope, identity, digest)
        except sqlite3.Error:
            raise FreezeError("STOP_WRITERS", "SQLITE_FREEZE_LOCK_FAILED") from None
        finally:
            if lock is not None:
                lock.rollback()
                lock.close()
        if any(self.ops.unit_active(unit) for unit in UNITS):
            raise FreezeError("POST_FREEZE_CHECK", "WRITER_RESTARTED")
        final_bytes, final_auth, final_envelope, _ = _check_snapshot(manifest, self.paths)
        # Export envelopes include a new capturedAt value on every read. Compare the
        # immutable source revision/hash, not that per-read metadata, after unlocking.
        if (final_envelope["revision"] != envelope["revision"]
                or final_envelope["sourceSha256"] != envelope["sourceSha256"]
                or _sha(final_auth) != _sha(auth_bytes)):
            raise FreezeError("POST_FREEZE_CHECK", "SOURCE_CHANGED_AFTER_UNLOCK")
        metadata_raw = (destination / "freeze-manifest.json").read_bytes()
        metadata = json.loads(metadata_raw)
        completion = {"schema": "siyue.final-source-freeze-complete/v1",
                      "freezeManifestSha256": _sha(metadata_raw),
                      "businessStateSha256": manifest["businessStateSha256"],
                      "authSha256": manifest["authSha256"], "revision": manifest["businessRevision"],
                      "verifiedAt": datetime.now(timezone.utc).isoformat(),
                      "writersStoppedAtFinalCheck": True}
        completion_raw = (json.dumps(completion, sort_keys=True) + "\n").encode()
        _write_private(destination / "freeze-complete.json", completion_raw)
        metadata["complete"] = True
        metadata["completionMarkerSha256"] = _sha(completion_raw)
        return metadata

    def _validate_feedback(self, business_bytes: bytes, auth_bytes: bytes) -> None:
        try:
            spec = importlib.util.spec_from_file_location("freeze_feedback_validate", self.paths.feedback)
            if spec is None or spec.loader is None:
                raise ValueError
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            module.combine(business_bytes, auth_bytes)
        except Exception:
            raise FreezeError("SOURCE_VALIDATE", "FEEDBACK_SOURCE_INVALID") from None

    def _create_bundle(self, destination: Path, business_bytes: bytes, auth_bytes: bytes,
                       envelope: dict, identity: dict, approval_digest: str) -> None:
        try:
            self.paths.freeze_root.mkdir(mode=0o700, parents=True, exist_ok=True)
            _private_directory(self.paths.freeze_root, self.paths.owner_uid)
            temp = Path(tempfile.mkdtemp(prefix=".freeze-", dir=self.paths.freeze_root))
            os.chmod(temp, 0o700)
            try:
                # Use exactly the reviewed merge contract; neither source is emitted to stdout.
                spec = importlib.util.spec_from_file_location("freeze_feedback", self.paths.feedback)
                if spec is None or spec.loader is None:
                    raise FreezeError("MERGE", "MERGE_TOOL_UNAVAILABLE")
                module = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(module)
                merged = module.combine(business_bytes, auth_bytes)
                merged_bytes = (json.dumps(merged, ensure_ascii=False, separators=(",", ":")) + "\n").encode()
                _write_private(temp / "source.json", merged_bytes)
                _write_private(temp / "auth-source.json", auth_bytes)
                audit = envelope["auditProvenance"]
                meta = {"schema": "siyue.final-source-freeze/v1", "freezeId": destination.name,
                        "createdAt": datetime.now(timezone.utc).isoformat(), "approvalSha256": approval_digest,
                        "revision": envelope["revision"], "businessStateSha256": envelope["sourceSha256"],
                        "authSha256": _sha(auth_bytes), "businessEnvelopeSha256": _sha(business_bytes),
                        "mergedSourceSha256": _sha(merged_bytes), "auditEventCount": len(audit),
                        "rewardClaimCount": identity["rewardClaims"], "rewardKeyAvailable": True,
                        "sourceOwnerUids": {"business": identity["business"]["uid"],
                                             "auth": identity["auth"]["uid"]},
                        "credentialsExcludedFromBusinessEnvelope": True}
                _write_private(temp / "freeze-manifest.json", (json.dumps(meta, sort_keys=True) + "\n").encode())
                _fsync_dir(temp)
                os.rename(temp, destination)
                _fsync_dir(self.paths.freeze_root)
            except Exception:
                shutil.rmtree(temp, ignore_errors=True)
                raise
        except FreezeError:
            raise
        except OSError as exc:
            raise FreezeError("OUTPUT", type(exc).__name__) from None


def _write_private(path: Path, data: bytes) -> None:
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "wb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())


def _fsync_dir(path: Path) -> None:
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", default=str(APPROVAL_ROOT / "approved.json"))
    parser.add_argument("--execute", action="store_true", help="Stop the approved CRM writers and create the source freeze")
    parser.add_argument("--prepare", action="store_true", help="Read-only source probe used to prepare an approval manifest")
    parser.add_argument("--approved-manifest-sha256")
    args = parser.parse_args()
    runner = FreezeRunner()
    try:
        if args.execute and args.prepare:
            raise FreezeError("APPROVAL", "MODE_CONFLICT")
        if args.prepare:
            result = runner.prepare()
        elif args.execute:
            result = runner.execute(Path(args.manifest), args.approved_manifest_sha256)
        else:
            result = runner.plan(Path(args.manifest))
        print(json.dumps(result, sort_keys=True))
        return 0
    except FreezeError as exc:
        print(json.dumps({"result": "blocked", "stage": exc.stage, "category": exc.category}, sort_keys=True),
              file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
