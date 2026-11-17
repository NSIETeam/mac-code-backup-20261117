#!/usr/bin/env python3
"""Provision an empty, retained Java target on the approved local MySQL instance.

This tool deliberately stops before Flyway, data import, account grants, service start,
and proxy changes. It creates an empty schema and isolated DB identities, then stages
the verified JAR, private environment, systemd unit, and a retention marker.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import stat
import subprocess
import sys
import tempfile
from typing import Callable

ROOT = Path(__file__).resolve().parent
UNIT_TEMPLATE = ROOT.parent.parent / "implementation-20261014/deploy/aliyun/siyue-crm-java.service"
MYSQL_PORT = 50571
MYSQL_SERVICE = "mysql.service"
MYSQL_DATADIR = "/var/lib/mysql/"
TLS_INSTALL_DIR = Path("/opt/siyue-crm/tls")
TLS_TRUSTSTORE_DEST = TLS_INSTALL_DIR / "java-truststore.jks"
TLS_ARGFILE_DEST = TLS_INSTALL_DIR / "java-tls.args"
TLS_DROPIN_DIR = Path("/etc/systemd/system/siyue-crm-java.service.d")
TLS_DROPIN_NAME = "20-java-tls-truststore.conf"
TLS_DROPIN_DEST = TLS_DROPIN_DIR / TLS_DROPIN_NAME
JAVA_JAR_PATH = "/opt/siyue-crm/java-current/siyue-core.jar"
TLS_PREP_APPROVAL_PURPOSE = "siyue-production-keycloak-tls"
OPENSSL = "/usr/bin/openssl"
SCHEMA_RE = re.compile(r"siyue_migration_candidate_[a-f0-9]{12}\Z")
HASH_RE = re.compile(r"[a-f0-9]{64}\Z")
USER_RE = re.compile(r"[a-z][a-z0-9_]{1,30}\Z")
MYSQL_VERSION_RE = re.compile(r"8\.0\.46(?:-[A-Za-z0-9.+_~-]+)?\Z")
APPROVAL_FIELDS = {
    "approvedBy", "schema", "serverUuid", "mysqlPort", "mysqlService", "mysqlDatadir",
    "freezeId", "sourceRevision", "businessSourceSha256", "authSourceSha256",
    "businessSourcePath", "authSourcePath", "rewardKeyPath", "jarSha256", "jarPath",
    "keycloakRealmSpecSha256", "keycloakRealmSpecPath", "credentialProviderSha256",
    "credentialProviderPath", "serviceUnitSha256",
    "tlsCertificateApprovalPath", "tlsCertificateApprovalSha256", "tlsCertificateApprovalFileSha256",
    "tlsCaCertificatePath", "tlsCaCertificateSha256", "tlsCaFingerprintSha256",
    "tlsLeafCertificatePath", "tlsLeafCertificateSha256", "tlsLeafFingerprintSha256",
    "tlsIntermediatesPath", "tlsIntermediatesSha256",
    "tlsTruststorePath", "tlsTruststoreSha256", "tlsArgfilePath", "tlsArgfileSha256",
    "tlsDropinPath", "tlsDropinSha256", "tlsReviewPath", "tlsReviewSha256",
}
SECRET_FIELDS = {
    "redisHost", "redisPort", "redisUsername", "redisPassword",
    "redisTls", "credentialAdminResetClientSecret", "credentialChangeHmacSecret",
}
FIXED_ENV = {
    "SIYUE_CORE_BIND": "127.0.0.1",
    "SIYUE_CORE_PORT": "8081",
    "SIYUE_OIDC_ISSUER": "https://www.siyuejn.com/crm-auth/realms/siyue-production",
    "SIYUE_OIDC_AUDIENCE": "siyue-api",
    "SIYUE_OIDC_CLIENT_ID": "siyue-crm-browser",
    "SIYUE_OIDC_REDIRECT_URI": "https://www.siyuejn.com/crm/",
    "SIYUE_WEB_ORIGIN": "https://www.siyuejn.com",
    "SIYUE_CREDENTIAL_ADMIN_RESET_ENABLED": "true",
    "SIYUE_CREDENTIAL_ADMIN_RESET_ISSUER": "https://www.siyuejn.com/crm-auth/realms/siyue-production",
    "SIYUE_CREDENTIAL_ADMIN_RESET_MANAGEMENT_BASE_URI": "https://localhost:18450/crm-auth",
    "SIYUE_CREDENTIAL_ADMIN_RESET_CLIENT_ID": "siyue-java-admin-reset",
    "SIYUE_CREDENTIAL_ADMIN_RESET_TIMEOUT_SECONDS": "8",
    "SIYUE_CREDENTIAL_CHANGE_ENABLED": "true",
    "SIYUE_CREDENTIAL_CHANGE_ISSUER": "https://www.siyuejn.com/crm-auth/realms/siyue-production",
    "SIYUE_CREDENTIAL_CHANGE_MAX_CLOCK_SKEW_SECONDS": "60",
    "SIYUE_LEGACY_IDENTITY_MIGRATION_ENABLED": "false",
    "SIYUE_JDBC_MAX_POOL_SIZE": "10",
    "SIYUE_HTTP_MAX_THREADS": "64",
    "SIYUE_HTTP_MIN_SPARE_THREADS": "4",
    "SIYUE_HTTP_MAX_CONNECTIONS": "8192",
    "SIYUE_HTTP_ACCEPT_COUNT": "100",
}


class Refused(ValueError):
    """A fixed refusal code; never include supplied secrets or business values."""


def require(condition: bool, code: str) -> None:
    if not condition:
        raise Refused(code)


def digest_file(path: Path) -> str:
    require(path.is_file() and not path.is_symlink(), "ARTIFACT_FILE")
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def private_json(path: Path) -> dict[str, object]:
    path = path.absolute()
    for part in [*reversed(path.parents), path]:
        try:
            require(not stat.S_ISLNK(part.lstat().st_mode), "PRIVATE_INPUT_SYMLINK")
        except FileNotFoundError:
            raise Refused("PRIVATE_INPUT_MISSING") from None
    info = path.stat()
    require(stat.S_ISREG(info.st_mode), "PRIVATE_INPUT_NOT_REGULAR")
    require(info.st_uid == 0 and stat.S_IMODE(info.st_mode) == 0o600, "PRIVATE_INPUT_PERMISSIONS")
    require(info.st_size <= 1024 * 1024, "PRIVATE_INPUT_TOO_LARGE")
    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_unique_json)
    except (OSError, UnicodeError, json.JSONDecodeError):
        raise Refused("PRIVATE_INPUT_INVALID") from None
    require(type(value) is dict, "PRIVATE_INPUT_SHAPE")
    return value


def _unique_json(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        require(key not in result, "DUPLICATE_JSON_KEY")
        result[key] = value
    return result


def validate_approval(approval: dict[str, object], expected_sha256: str) -> None:
    require(set(approval) == APPROVAL_FIELDS, "APPROVAL_FIELDS")
    require(bool(HASH_RE.fullmatch(expected_sha256)), "APPROVAL_HASH_ARGUMENT")
    canonical = json.dumps(approval, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    require(hashlib.sha256(canonical).hexdigest() == expected_sha256, "APPROVAL_HASH_MISMATCH")
    require(bool(SCHEMA_RE.fullmatch(str(approval["schema"]))), "TARGET_SCHEMA")
    require(bool(re.fullmatch(r"[A-Fa-f0-9-]{36}", str(approval["serverUuid"]))), "TARGET_INSTANCE")
    require(approval["mysqlPort"] == MYSQL_PORT and approval["mysqlService"] == MYSQL_SERVICE
            and approval["mysqlDatadir"] == MYSQL_DATADIR, "TARGET_MYSQL_BOUNDARY")
    require(bool(re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", str(approval["freezeId"]))), "FREEZE_ID")
    require(type(approval["sourceRevision"]) is int and approval["sourceRevision"] >= 0, "SOURCE_REVISION")
    for field in ("businessSourceSha256", "authSourceSha256", "jarSha256",
                  "keycloakRealmSpecSha256", "credentialProviderSha256", "serviceUnitSha256",
                  "tlsCertificateApprovalFileSha256", "tlsCaCertificateSha256",
                  "tlsCaFingerprintSha256", "tlsLeafCertificateSha256", "tlsLeafFingerprintSha256",
                  "tlsTruststoreSha256", "tlsArgfileSha256", "tlsDropinSha256", "tlsReviewSha256"):
        require(bool(HASH_RE.fullmatch(str(approval[field]))), "APPROVAL_SOURCE_HASH")
    for field in ("businessSourcePath", "authSourcePath", "rewardKeyPath", "jarPath",
                  "keycloakRealmSpecPath", "credentialProviderPath", "tlsCertificateApprovalPath",
                  "tlsCaCertificatePath", "tlsLeafCertificatePath", "tlsTruststorePath",
                  "tlsArgfilePath", "tlsDropinPath", "tlsReviewPath"):
        require(type(approval[field]) is str and Path(approval[field]).is_absolute(), "APPROVAL_PATH")
    require(bool(HASH_RE.fullmatch(str(approval["tlsCertificateApprovalSha256"]))),
            "TLS_CERTIFICATE_APPROVAL_DIGEST")
    intermediate_path = approval["tlsIntermediatesPath"]
    intermediate_hash = approval["tlsIntermediatesSha256"]
    require((intermediate_path is None and intermediate_hash is None)
            or (type(intermediate_path) is str and Path(intermediate_path).is_absolute()
                and type(intermediate_hash) is str and HASH_RE.fullmatch(intermediate_hash)),
            "TLS_INTERMEDIATE_APPROVAL")
    target_sources = {
        str(Path(str(approval[field])).absolute())
        for field in ("tlsTruststorePath", "tlsArgfilePath", "tlsDropinPath", "tlsReviewPath")
    }
    require(str(TLS_TRUSTSTORE_DEST) not in target_sources
            and str(TLS_ARGFILE_DEST) not in target_sources
            and str(TLS_DROPIN_DEST) not in target_sources, "TLS_SOURCE_DESTINATION_COLLISION")
    require(str(approval["approvedBy"]).strip() != "", "APPROVAL_OWNER")


def validate_frozen_inputs(approval: dict[str, object]) -> dict[str, Path]:
    for path_field, hash_field in (("businessSourcePath", "businessSourceSha256"),
                                   ("authSourcePath", "authSourceSha256"),
                                   ("jarPath", "jarSha256"),
                                   ("keycloakRealmSpecPath", "keycloakRealmSpecSha256"),
                                   ("credentialProviderPath", "credentialProviderSha256")):
        source = Path(str(approval[path_field]))
        require_regular_path(source, path_field in {"businessSourcePath", "authSourcePath"})
        require(digest_file(source) == approval[hash_field], "APPROVED_ARTIFACT_HASH")
    reward_key = Path(str(approval["rewardKeyPath"]))
    require_regular_path(reward_key, True)
    metadata = reward_key.stat()
    require(metadata.st_uid == 0 and stat.S_IMODE(metadata.st_mode) == 0o600 and metadata.st_size >= 32,
            "REWARD_KEY_PERMISSIONS")
    return validate_tls_bundle(approval)


def require_root_artifact(path: Path, expected_mode: int | None = None) -> None:
    path = path.absolute()
    for parent in reversed(path.parents):
        try:
            info = parent.lstat()
        except FileNotFoundError:
            raise Refused("TLS_ARTIFACT_MISSING") from None
        require(not stat.S_ISLNK(info.st_mode) and stat.S_ISDIR(info.st_mode) and info.st_uid == 0,
                "TLS_ARTIFACT_PARENT")
        parent_mode = stat.S_IMODE(info.st_mode)
        require(parent_mode & 0o022 == 0 or bool(parent_mode & stat.S_ISVTX), "TLS_ARTIFACT_PARENT_MODE")
    try:
        info = path.lstat()
    except FileNotFoundError:
        raise Refused("TLS_ARTIFACT_MISSING") from None
    require(stat.S_ISREG(info.st_mode) and info.st_uid == 0, "TLS_ARTIFACT_OWNER")
    require(stat.S_IMODE(info.st_mode) & 0o022 == 0, "TLS_ARTIFACT_WRITABLE")
    if expected_mode is not None:
        require(stat.S_IMODE(info.st_mode) == expected_mode, "TLS_ARTIFACT_MODE")


def read_json_artifact(path: Path, expected_mode: int) -> dict[str, object]:
    require_root_artifact(path, expected_mode)
    require(path.stat().st_size <= 128 * 1024, "TLS_REVIEW_TOO_LARGE")
    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_unique_json)
    except (OSError, UnicodeError, json.JSONDecodeError):
        raise Refused("TLS_REVIEW_INVALID") from None
    require(type(value) is dict, "TLS_REVIEW_SHAPE")
    return value


def openssl_result(arguments: list[str]) -> str:
    result = subprocess.run([OPENSSL, *arguments], text=True, capture_output=True,
                            check=False, timeout=20)
    require(result.returncode == 0, "TLS_CERTIFICATE_CHECK")
    return result.stdout + result.stderr


def certificate_fingerprint(path: Path) -> tuple[str, str]:
    report = openssl_result(["x509", "-in", str(path), "-noout", "-text", "-fingerprint", "-sha256",
                             "-checkend", "0"])
    match = re.search(r"(?im)^\s*sha256 Fingerprint=([0-9a-f:]+)\s*$", report)
    require(match is not None, "TLS_CERTIFICATE_FINGERPRINT")
    fingerprint = match.group(1).replace(":", "").lower()
    require(bool(HASH_RE.fullmatch(fingerprint)), "TLS_CERTIFICATE_FINGERPRINT")
    return fingerprint, report


def verify_tls_certificates(approval: dict[str, object]) -> None:
    ca = Path(str(approval["tlsCaCertificatePath"]))
    leaf = Path(str(approval["tlsLeafCertificatePath"]))
    ca_fp, ca_report = certificate_fingerprint(ca)
    leaf_fp, leaf_report = certificate_fingerprint(leaf)
    require(ca_fp == approval["tlsCaFingerprintSha256"]
            and leaf_fp == approval["tlsLeafFingerprintSha256"], "TLS_CERTIFICATE_FINGERPRINT_MISMATCH")
    require(re.search(r"(?m)^\s*CA:TRUE\s*$", ca_report) is not None, "TLS_CA_CONSTRAINT")
    key_usage = re.search(r"X509v3 Key Usage(?::[^\n]*)?\n\s*([^\n]+)", ca_report)
    require(key_usage is not None and "Certificate Sign" in key_usage.group(1), "TLS_CA_KEY_USAGE")
    require(re.search(r"(?m)^\s*CA:TRUE\s*$", leaf_report) is None, "TLS_LEAF_IS_CA")
    san = re.search(r"X509v3 Subject Alternative Name(?::[^\n]*)?\n\s*([^\n]+)", leaf_report)
    san_entries = [item.strip() for item in san.group(1).split(",")] if san else []
    require(san_entries.count("DNS:localhost") == 1
            and not any(item.lower().startswith("dns:") and item != "DNS:localhost"
                        for item in san_entries), "TLS_LEAF_LOCALHOST_SAN")
    command = ["verify", "-purpose", "sslserver", "-CAfile", str(ca)]
    if approval["tlsIntermediatesPath"] is not None:
        command.extend(["-untrusted", str(approval["tlsIntermediatesPath"])])
    command.append(str(leaf))
    openssl_result(command)


def validate_argfile(path: Path) -> str:
    try:
        lines = path.read_text(encoding="ascii").splitlines()
    except (OSError, UnicodeError):
        raise Refused("TLS_ARGFILE_INVALID") from None
    require(len(lines) == 3, "TLS_ARGFILE_INVALID")
    values = {}
    for line in lines:
        require(line.startswith("-D") and "=" in line, "TLS_ARGFILE_INVALID")
        key, value = line[2:].split("=", 1)
        require(key not in values, "TLS_ARGFILE_DUPLICATE_PROPERTY")
        values[key] = value
    require(set(values) == {"javax.net.ssl.trustStore", "javax.net.ssl.trustStoreType",
                            "javax.net.ssl.trustStorePassword"}, "TLS_ARGFILE_PROPERTIES")
    require(values["javax.net.ssl.trustStore"] == str(TLS_TRUSTSTORE_DEST), "TLS_ARGFILE_TRUSTSTORE")
    require(values["javax.net.ssl.trustStoreType"] in {"JKS", "PKCS12"}, "TLS_ARGFILE_TYPE")
    require(bool(re.fullmatch(r"[A-Za-z0-9]{40,128}", values["javax.net.ssl.trustStorePassword"])),
            "TLS_ARGFILE_PASSWORD_FORMAT")
    return values["javax.net.ssl.trustStoreType"]


def expected_tls_dropin() -> bytes:
    return ("[Service]\nExecStart=\n"
            f"ExecStart=/usr/bin/java @{TLS_ARGFILE_DEST} -XX:MaxRAMPercentage=65 "
            f"-XX:+ExitOnOutOfMemoryError -jar {JAVA_JAR_PATH}\n").encode("ascii")


def validate_tls_bundle(approval: dict[str, object]) -> dict[str, Path]:
    inputs = {
        "certificateApproval": ("tlsCertificateApprovalPath", "tlsCertificateApprovalFileSha256", 0o600),
        "ca": ("tlsCaCertificatePath", "tlsCaCertificateSha256", None),
        "leaf": ("tlsLeafCertificatePath", "tlsLeafCertificateSha256", None),
        "truststore": ("tlsTruststorePath", "tlsTruststoreSha256", 0o640),
        "argfile": ("tlsArgfilePath", "tlsArgfileSha256", 0o640),
        "dropin": ("tlsDropinPath", "tlsDropinSha256", 0o644),
        "review": ("tlsReviewPath", "tlsReviewSha256", 0o640),
    }
    if approval["tlsIntermediatesPath"] is not None:
        inputs["intermediates"] = ("tlsIntermediatesPath", "tlsIntermediatesSha256", None)
    sources: dict[str, Path] = {}
    for label, (path_field, hash_field, mode) in inputs.items():
        source = Path(str(approval[path_field]))
        require_root_artifact(source, mode)
        require(digest_file(source) == approval[hash_field], "TLS_ARTIFACT_HASH")
        sources[label] = source
    intermediate_path = approval["tlsIntermediatesPath"]
    if intermediate_path is None:
        require(approval["tlsIntermediatesSha256"] is None, "TLS_INTERMEDIATE_APPROVAL")
    else:
        require(type(intermediate_path) is str and sources["intermediates"].is_file(),
                "TLS_INTERMEDIATE_APPROVAL")
    verify_tls_certificates(approval)
    cert_approval = read_json_artifact(sources["certificateApproval"], 0o600)
    require(set(cert_approval) == {"purpose", "serverName", "caSha256", "leafSha256"}
            and cert_approval["purpose"] == TLS_PREP_APPROVAL_PURPOSE
            and cert_approval["serverName"] == "localhost"
            and cert_approval["caSha256"] == approval["tlsCaFingerprintSha256"]
            and cert_approval["leafSha256"] == approval["tlsLeafFingerprintSha256"],
            "TLS_CERTIFICATE_APPROVAL_CONTENT")
    cert_approval_hash = hashlib.sha256(
        json.dumps(cert_approval, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    require(cert_approval_hash == approval["tlsCertificateApprovalSha256"], "TLS_CERTIFICATE_APPROVAL_HASH")
    review = read_json_artifact(sources["review"], 0o640)
    require(review.get("purpose") == TLS_PREP_APPROVAL_PURPOSE and review.get("jdkMajor") == 17
            and review.get("productionCaSha256") == approval["tlsCaFingerprintSha256"]
            and review.get("leafCertificateSha256") == approval["tlsLeafFingerprintSha256"]
            and review.get("requiredSan") == "DNS:localhost" and review.get("certChainVerified") is True
            and review.get("publicRootsPreserved") is True and review.get("installed") is False,
            "TLS_REVIEW_CONTENT")
    require(review.get("approvalSha256") == cert_approval_hash, "TLS_REVIEW_APPROVAL_HASH")
    require(review.get("truststoreType") in {"JKS", "PKCS12"}
            and bool(HASH_RE.fullmatch(str(review.get("sourceCacertsSha256", ""))))
            and type(review.get("sourceTrustedCertificateCount")) is int
            and type(review.get("resultTrustedCertificateCount")) is int
            and review["sourceTrustedCertificateCount"] > 0
            and review["resultTrustedCertificateCount"] == review["sourceTrustedCertificateCount"] + 1,
            "TLS_REVIEW_STORE_COUNTS")
    require(review.get("ownership") == "root:siyue-crm-java"
            and review.get("modes") == {"directory": "0750", "truststore": "0640",
                                        "argfile": "0640", "dropin": "0644"},
            "TLS_REVIEW_PERMISSIONS")
    require(review.get("installPaths") == {
        "truststore": str(TLS_TRUSTSTORE_DEST),
        "argfile": str(TLS_ARGFILE_DEST),
        "dropin": str(TLS_DROPIN_DIR / TLS_DROPIN_NAME),
    }, "TLS_REVIEW_INSTALL_PATHS")
    file_hashes = review.get("files")
    require(file_hashes == {
        "truststoreSha256": approval["tlsTruststoreSha256"],
        "argfileSha256": approval["tlsArgfileSha256"],
        "dropinSha256": approval["tlsDropinSha256"],
    }, "TLS_REVIEW_ARTIFACT_HASHES")
    truststore_type = validate_argfile(sources["argfile"])
    require(truststore_type == review["truststoreType"], "TLS_REVIEW_STORE_TYPE")
    try:
        dropin = sources["dropin"].read_bytes()
    except OSError:
        raise Refused("TLS_DROPIN_INVALID") from None
    require(dropin == expected_tls_dropin(), "TLS_DROPIN_CONTENT")
    destinations = {TLS_TRUSTSTORE_DEST, TLS_ARGFILE_DEST, TLS_DROPIN_DEST}
    require(not any(source in destinations for source in sources.values()), "TLS_SOURCE_DESTINATION_COLLISION")
    return sources


def require_regular_path(path: Path, private: bool) -> None:
    path = path.absolute()
    for part in [*reversed(path.parents), path]:
        try:
            require(not stat.S_ISLNK(part.lstat().st_mode), "APPROVED_PATH_SYMLINK")
        except FileNotFoundError:
            raise Refused("APPROVED_ARTIFACT_MISSING") from None
    info = path.stat()
    require(stat.S_ISREG(info.st_mode) and info.st_uid == 0, "APPROVED_ARTIFACT_OWNER")
    if private:
        require(stat.S_IMODE(info.st_mode) == 0o600, "APPROVED_ARTIFACT_PERMISSIONS")


def validate_secret_input(value: dict[str, object]) -> None:
    require(set(value) == SECRET_FIELDS, "SECRET_FIELDS")
    for field in ("redisHost", "redisUsername", "redisPassword", "credentialAdminResetClientSecret",
                  "credentialChangeHmacSecret"):
        require(isinstance(value[field], str) and value[field] != "", "SECRET_INPUT_VALUE")
        require("\n" not in value[field] and "\r" not in value[field], "SECRET_INPUT_NEWLINE")
    require(type(value["redisPort"]) is int and 1 <= value["redisPort"] <= 65535, "REDIS_PORT")
    require(type(value["redisTls"]) is bool, "REDIS_TLS")
    require(bool(re.fullmatch(r"[A-Za-z0-9_-]{32,256}", str(value["credentialChangeHmacSecret"]))),
            "EVENT_HMAC_FORMAT")


def sql_string(value: str) -> str:
    require("\x00" not in value and "\n" not in value and "\r" not in value, "SQL_STRING")
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def env_quote(value: str) -> str:
    require("\n" not in value and "\r" not in value, "ENV_NEWLINE")
    escaped = value.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$").replace("`", "\\`")
    return f'"{escaped}"'


def database_accounts(schema: str) -> dict[str, str]:
    token = schema.rsplit("_", 1)[-1]
    return {
        "runtimeUser": f"sy_rt_{token}",
        "migrationUser": f"sy_mig_{token}",
        "viewOwnerUser": f"sy_view_{token}",
    }


def account_sql(schema: str, names: dict[str, str], passwords: dict[str, str]) -> str:
    require(bool(SCHEMA_RE.fullmatch(schema)), "TARGET_SCHEMA")
    require(set(names) == {"runtimeUser", "migrationUser", "viewOwnerUser"}, "ACCOUNT_NAMES")
    require(set(passwords) == set(names), "ACCOUNT_PASSWORDS")
    for user in names.values():
        require(bool(USER_RE.fullmatch(user)), "ACCOUNT_NAME")
    statements = [
        f"CREATE DATABASE `{schema}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;",
        "-- Accounts begin with no object grants. Apply reviewed per-table grants only after Flyway.",
    ]
    for key in ("runtimeUser", "migrationUser", "viewOwnerUser"):
        statements.append(f"CREATE USER {sql_string(names[key])}@'127.0.0.1' IDENTIFIED BY {sql_string(passwords[key])} ACCOUNT LOCK;")
    return "\n".join(statements) + "\n"


def runtime_environment(schema: str, names: dict[str, str], passwords: dict[str, str],
                        secret_input: dict[str, object]) -> str:
    token = secrets.token_urlsafe(32)
    pii_id = "prod-" + schema.rsplit("_", 1)[-1]
    import base64
    pii_key = base64.b64encode(secrets.token_bytes(32)).decode("ascii")
    env: dict[str, str] = dict(FIXED_ENV)
    env.update({
        "SIYUE_MYSQL_URL": f"jdbc:mysql://127.0.0.1:{MYSQL_PORT}/{schema}?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&sslMode=REQUIRED",
        "SIYUE_MYSQL_USER": names["runtimeUser"],
        "SIYUE_MYSQL_PASSWORD": passwords["runtimeUser"],
        "SIYUE_REDIS_HOST": str(secret_input["redisHost"]),
        "SIYUE_REDIS_PORT": str(secret_input["redisPort"]),
        "SIYUE_REDIS_USERNAME": str(secret_input["redisUsername"]),
        "SIYUE_REDIS_PASSWORD": str(secret_input["redisPassword"]),
        "SIYUE_REDIS_TLS": "true" if secret_input["redisTls"] else "false",
        "SIYUE_PII_KEYS_JSON": json.dumps({pii_id: pii_key}, separators=(",", ":")),
        "SIYUE_PII_ACTIVE_KEY_ID": pii_id,
        "SIYUE_LEGACY_VISITOR_SECRET": token,
        "SIYUE_CREDENTIAL_ADMIN_RESET_CLIENT_SECRET": str(secret_input["credentialAdminResetClientSecret"]),
        "SIYUE_CREDENTIAL_CHANGE_HMAC_SECRET": str(secret_input["credentialChangeHmacSecret"]),
    })
    return "\n".join(f"{key}={env_quote(env[key])}" for key in sorted(env)) + "\n"


def safe_write(path: Path, data: bytes, mode: int) -> None:
    require(not path.exists() and not path.is_symlink(), "TARGET_PATH_EXISTS")
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o750)
    require(not path.parent.is_symlink(), "TARGET_PARENT_SYMLINK")
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), mode)
    with os.fdopen(fd, "wb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    os.chmod(path, mode)


def atomic_copy_approved(source: Path, destination: Path, expected_sha256: str, mode: int,
                         owner_uid: int, group_gid: int) -> str:
    require(destination.parent.is_dir() and not destination.parent.is_symlink(), "TLS_TARGET_DIRECTORY")
    require(not destination.exists() and not destination.is_symlink(), "TLS_TARGET_EXISTS")
    temporary = destination.parent / ("." + destination.name + ".stage-" + secrets.token_hex(8))
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
    copied_hash = hashlib.sha256()
    try:
        with source.open("rb") as input_stream, os.fdopen(fd, "wb", closefd=False) as output_stream:
            for chunk in iter(lambda: input_stream.read(1024 * 1024), b""):
                copied_hash.update(chunk)
                output_stream.write(chunk)
            output_stream.flush()
            os.fsync(fd)
        digest = copied_hash.hexdigest()
        require(digest == expected_sha256, "TLS_COPY_HASH")
        os.fchown(fd, owner_uid, group_gid)
        os.fchmod(fd, mode)
        os.fsync(fd)
        os.close(fd)
        fd = -1
        os.link(temporary, destination, follow_symlinks=False)
        directory_fd = os.open(destination.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
        return digest
    finally:
        if fd >= 0:
            os.close(fd)
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass


def stage_tls_files(approval: dict[str, object], sources: dict[str, Path], base: Path,
                    service_uid: int, service_gid: int) -> dict[str, str]:
    tls_dir = under_root(base, TLS_INSTALL_DIR)
    dropin_dir = under_root(base, TLS_DROPIN_DIR)
    require(tls_dir.is_dir() and not tls_dir.is_symlink(), "TLS_TARGET_DIRECTORY")
    require(dropin_dir.is_dir() and not dropin_dir.is_symlink(), "TLS_DROPIN_DIRECTORY")
    installed = {
        "truststoreSha256": atomic_copy_approved(
            sources["truststore"], tls_dir / TLS_TRUSTSTORE_DEST.name,
            str(approval["tlsTruststoreSha256"]), 0o640, service_uid, service_gid),
        "argfileSha256": atomic_copy_approved(
            sources["argfile"], tls_dir / TLS_ARGFILE_DEST.name,
            str(approval["tlsArgfileSha256"]), 0o640, service_uid, service_gid),
        "dropinSha256": atomic_copy_approved(
            sources["dropin"], dropin_dir / TLS_DROPIN_NAME,
            str(approval["tlsDropinSha256"]), 0o644, 0, 0),
    }
    return installed


def check_mysql_target(run: Callable[..., subprocess.CompletedProcess[str]], admin_cnf: Path,
                       expected_uuid: str) -> None:
    require_regular_path(admin_cnf, True)
    unit = run(["systemctl", "show", MYSQL_SERVICE, "--property=LoadState,ActiveState,SubState"],
               capture_output=True, text=True, check=False)
    require(unit.returncode == 0, "MYSQL_SERVICE_STATE")
    state = parse_systemd_properties(unit.stdout, {"LoadState", "ActiveState", "SubState"})
    require(state == {"LoadState": "loaded", "ActiveState": "active", "SubState": "running"},
            "MYSQL_SERVICE_STATE")
    query = "SELECT @@server_uuid,@@port,@@datadir,@@log_bin,@@log_bin_trust_function_creators,VERSION(),@@have_ssl;"
    result = run(["mysql", f"--defaults-extra-file={admin_cnf}", "--protocol=TCP", "-h127.0.0.1",
                  f"-P{MYSQL_PORT}", "--ssl-mode=REQUIRED", "--batch", "--skip-column-names", "-e", query],
                 capture_output=True, text=True, check=False)
    require(result.returncode == 0, "MYSQL_PROBE_FAILED")
    validate_mysql_facts(result.stdout.strip().split("\t"), expected_uuid)


def validate_mysql_facts(fields: list[str], expected_uuid: str) -> None:
    require(len(fields) == 7 and fields[0] == expected_uuid and fields[1] == str(MYSQL_PORT)
            and fields[2] == MYSQL_DATADIR and fields[3].lower() == "1"
            and fields[4] in {"0", "OFF"} and bool(MYSQL_VERSION_RE.fullmatch(fields[5]))
            and fields[6].upper() in {"YES", "ON", "1"}, "MYSQL_INSTANCE_MISMATCH")


def parse_systemd_properties(output: str, required: set[str]) -> dict[str, str]:
    properties: dict[str, str] = {}
    for line in output.splitlines():
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key in required:
            require(key not in properties, "SYSTEMD_PROPERTY_DUPLICATE")
            properties[key] = value
    require(set(properties) == required, "SYSTEMD_PROPERTY_MISSING")
    return properties


def require_secure_target_ancestors(path: Path) -> None:
    path = path.absolute()
    for component in reversed(path.parents):
        try:
            info = component.lstat()
        except FileNotFoundError:
            continue
        require(not stat.S_ISLNK(info.st_mode), "TARGET_ANCESTOR_SYMLINK")
        require(stat.S_ISDIR(info.st_mode) and info.st_uid == 0, "TARGET_ANCESTOR_OWNER")
        require(stat.S_IMODE(info.st_mode) & 0o022 == 0, "TARGET_ANCESTOR_WRITABLE")


def under_root(root: Path, absolute_path: Path) -> Path:
    require(absolute_path.is_absolute(), "TARGET_PATH_ABSOLUTE")
    return root / absolute_path.relative_to("/")


def plan_summary(approval: dict[str, object]) -> dict[str, object]:
    return {"mode": "plan", "schema": approval["schema"], "mysqlPort": MYSQL_PORT,
            "jarSha256": approval["jarSha256"], "flywayRun": False, "importRun": False,
            "serviceStart": False, "routeChange": False, "credentialsPrinted": False}


def validate_target_available(approval: dict[str, object], jar: Path, admin_cnf: Path,
                              run: Callable[..., subprocess.CompletedProcess[str]], root: Path = Path("/")) -> dict[str, Path]:
    require(os.geteuid() == 0, "ROOT_REQUIRED")
    tls_sources = validate_frozen_inputs(approval)
    require(digest_file(jar) == approval["jarSha256"], "JAR_HASH_MISMATCH")
    require(digest_file(UNIT_TEMPLATE) == approval["serviceUnitSha256"], "SERVICE_UNIT_HASH")
    check_mysql_target(run, admin_cnf, str(approval["serverUuid"]))
    mysql_data = root / "var/lib/mysql"
    require(mysql_data.is_dir() and not mysql_data.is_symlink(), "MYSQL_DATADIR_NOT_PERSISTENT")
    service_state = run(["systemctl", "show", "siyue-crm-java.service",
                         "--property=LoadState,ActiveState,SubState"],
                        capture_output=True, text=True, check=False)
    require(service_state.returncode == 0, "JAVA_SERVICE_ALREADY_PRESENT")
    properties = parse_systemd_properties(service_state.stdout, {"LoadState", "ActiveState", "SubState"})
    require(properties == {"LoadState": "not-found", "ActiveState": "inactive", "SubState": "dead"},
            "JAVA_SERVICE_ALREADY_PRESENT")
    java = run(["/usr/bin/java", "-version"], capture_output=True, text=True, check=False)
    require(java.returncode == 0 and bool(re.search(r'\b(?:openjdk|java) version "17(?:\.|")',
                                                       java.stdout + java.stderr)), "JAVA17_REQUIRED")
    schema = str(approval["schema"])
    query = "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=" + sql_string(schema) + ";"
    schema_probe = run(["mysql", f"--defaults-extra-file={admin_cnf}", "--protocol=TCP", "-h127.0.0.1",
                        f"-P{MYSQL_PORT}", "--batch", "--skip-column-names", "-e", query],
                       capture_output=True, text=True, check=False)
    require(schema_probe.returncode == 0 and schema_probe.stdout.strip() == "0", "TARGET_SCHEMA_EXISTS")
    accounts = database_accounts(schema)
    account_names = ",".join(sql_string(accounts[key])
                               for key in ("runtimeUser", "migrationUser", "viewOwnerUser"))
    account_probe = run(["mysql", f"--defaults-extra-file={admin_cnf}", "--protocol=TCP", "-h127.0.0.1",
                         f"-P{MYSQL_PORT}", "--batch", "--skip-column-names", "-e",
                         "SELECT COUNT(*) FROM mysql.user WHERE Host='127.0.0.1' AND User IN (" + account_names + ");"],
                        capture_output=True, text=True, check=False)
    require(account_probe.returncode == 0 and account_probe.stdout.strip() == "0", "TARGET_ACCOUNT_EXISTS")
    base = root
    absent_paths = [base / "opt/siyue-crm/releases" / str(approval["jarSha256"]),
                    base / "opt/siyue-crm/java-current", base / "etc/siyue-crm/java.env",
                    base / "etc/systemd/system/siyue-crm-java.service",
                    under_root(base, TLS_INSTALL_DIR),
                    under_root(base, TLS_DROPIN_DEST),
                    base / "var/lib/siyue-crm/production-retention" / f"{approval['schema']}.json"]
    require(all(not path.exists() and not path.is_symlink() for path in absent_paths), "TARGET_PATH_EXISTS")
    for path in absent_paths:
        require_secure_target_ancestors(path)
    return tls_sources


def prepare_directories(base: Path, run: Callable[..., subprocess.CompletedProcess[str]]) -> None:
    account = run(["getent", "passwd", "siyue-crm-java"], capture_output=True, text=True, check=False)
    require(account.returncode in {0, 2}, "SERVICE_ACCOUNT_LOOKUP")
    if account.returncode == 2:
        created = run(["useradd", "--system", "--user-group", "--home-dir", "/nonexistent",
                       "--shell", "/usr/sbin/nologin", "siyue-crm-java"],
                      capture_output=True, text=True, check=False)
        require(created.returncode == 0, "SERVICE_ACCOUNT_CREATE")
    else:
        fields = account.stdout.strip().split(":")
        require(len(fields) == 7 and fields[0] == "siyue-crm-java"
                and fields[6] in {"/usr/sbin/nologin", "/sbin/nologin"}, "SERVICE_ACCOUNT_STATE")
    for relative in ("opt/siyue-crm", "opt/siyue-crm/releases", "opt/siyue-crm/tls", "etc/siyue-crm",
                     "etc/siyue-crm/private", "etc/systemd/system",
                     "etc/systemd/system/siyue-crm-java.service.d",
                     "var/lib/siyue-crm", "var/lib/siyue-crm/production-retention"):
        target = base / relative
        require(not target.is_symlink(), "TARGET_DIRECTORY_SYMLINK")
        if not target.exists():
            target.mkdir(mode=0o700 if "private" in relative else
                         0o755 if relative in {"opt/siyue-crm", "opt/siyue-crm/releases"} else 0o750)
        require(not target.is_symlink(), "TARGET_DIRECTORY_SYMLINK")
        metadata = target.stat()
        require(stat.S_ISDIR(metadata.st_mode) and metadata.st_uid == 0, "TARGET_DIRECTORY_OWNER")
        mode = stat.S_IMODE(metadata.st_mode)
        if "private" in relative:
            require(mode & 0o077 == 0, "PRIVATE_DIRECTORY_MODE")
        else:
            require(mode & 0o022 == 0, "TARGET_DIRECTORY_WRITABLE")
        if relative in {"opt/siyue-crm", "opt/siyue-crm/releases"}:
            require(mode & 0o001 == 0o001, "RELEASE_PARENT_NOT_SEARCHABLE")
        if relative == "opt/siyue-crm/tls":
            chowned = run(["chown", "root:siyue-crm-java", str(target)],
                          capture_output=True, text=True, check=False)
            require(chowned.returncode == 0, "TLS_DIRECTORY_GROUP")
            os.chmod(target, 0o750)


def apply_provision(approval: dict[str, object], jar: Path, unit_template: Path,
                    admin_cnf: Path, secret_input: dict[str, object], run: Callable[..., subprocess.CompletedProcess[str]],
                    root: Path = Path("/")) -> dict[str, object]:
    validate_secret_input(secret_input)
    tls_sources = validate_target_available(approval, jar, admin_cnf, run, root)
    require(digest_file(unit_template) == approval["serviceUnitSha256"], "SERVICE_UNIT_HASH")
    schema = str(approval["schema"])
    names = database_accounts(schema)
    passwords = {key: secrets.token_urlsafe(40) for key in names}
    require(unit_template.is_file() and not unit_template.is_symlink(), "UNIT_TEMPLATE")

    base = root
    prepare_directories(base, run)
    release_dir = base / "opt/siyue-crm/releases" / str(approval["jarSha256"])
    marker_dir = base / "var/lib/siyue-crm/production-retention"
    marker_path = marker_dir / f"{schema}.json"
    env_path = base / "etc/siyue-crm/java.env"
    unit_path = base / "etc/systemd/system/siyue-crm-java.service"
    current = base / "opt/siyue-crm/java-current"
    credentials_path = base / "etc/siyue-crm/private" / f"{schema}-database-identities.json"
    require(not release_dir.exists() and not marker_path.exists() and not env_path.exists()
            and not unit_path.exists() and not current.exists() and not current.is_symlink()
            and not credentials_path.exists(), "TARGET_PATH_EXISTS")
    marker: dict[str, object] = {
        "kind": "production-retained-java-target",
        "schema": schema,
        "serverUuid": approval["serverUuid"],
        "mysqlPort": MYSQL_PORT,
        "freezeId": approval["freezeId"],
        "sourceRevision": approval["sourceRevision"],
        "businessSourceSha256": approval["businessSourceSha256"],
        "authSourceSha256": approval["authSourceSha256"],
        "jarSha256": approval["jarSha256"],
        "keycloakRealmSpecSha256": approval["keycloakRealmSpecSha256"],
        "credentialProviderSha256": approval["credentialProviderSha256"],
        "serviceUnitSha256": approval["serviceUnitSha256"],
        "tlsCertificateApprovalSha256": approval["tlsCertificateApprovalSha256"],
        "tlsCertificateApprovalPath": approval["tlsCertificateApprovalPath"],
        "tlsCaCertificateSha256": approval["tlsCaCertificateSha256"],
        "tlsCaFingerprintSha256": approval["tlsCaFingerprintSha256"],
        "tlsLeafCertificateSha256": approval["tlsLeafCertificateSha256"],
        "tlsLeafFingerprintSha256": approval["tlsLeafFingerprintSha256"],
        "tlsTruststoreSha256": approval["tlsTruststoreSha256"],
        "tlsArgfileSha256": approval["tlsArgfileSha256"],
        "tlsDropinSha256": approval["tlsDropinSha256"],
        "tlsReviewSha256": approval["tlsReviewSha256"],
        "tlsReviewPath": approval["tlsReviewPath"],
        "serviceDropinName": TLS_DROPIN_NAME,
        "serviceEffectiveInputsSha256": hashlib.sha256(
            (str(approval["serviceUnitSha256"]) + "\n" + str(approval["tlsDropinSha256"])).encode()).hexdigest(),
        "state": "provisioning_incomplete",
        "retainUntilReviewedRemoval": True,
        "serviceStarted": False,
        "routeChanged": False,
    }
    safe_write(marker_path, (json.dumps(marker, sort_keys=True, indent=2) + "\n").encode(), 0o400)

    # Persist recovery inputs before non-transactional MySQL DDL. A partial CREATE
    # USER failure must not strand an account whose generated password is lost.
    safe_write(credentials_path,
               (json.dumps({"users": names, "passwords": passwords}, sort_keys=True) + "\n").encode(), 0o600)
    safe_write(env_path, runtime_environment(schema, names, passwords, secret_input).encode(), 0o600)

    sql = account_sql(schema, names, passwords)
    created = run(["mysql", f"--defaults-extra-file={admin_cnf}", "--protocol=TCP", "-h127.0.0.1",
                   f"-P{MYSQL_PORT}", "--batch", "--skip-column-names"], input=sql,
                  capture_output=True, text=True, check=False)
    require(created.returncode == 0, "DB_BOOTSTRAP_FAILED")

    release_dir.mkdir(parents=True, mode=0o750, exist_ok=False)
    jar_target = release_dir / "siyue-core.jar"
    shutil.copyfile(jar, jar_target, follow_symlinks=False)
    os.chmod(jar_target, 0o640)
    run(["chown", "-R", "root:siyue-crm-java", str(release_dir)],
        capture_output=True, text=True, check=True)
    require(digest_file(jar_target) == approval["jarSha256"], "STAGED_JAR_HASH")
    current.symlink_to(release_dir)

    service_uid = 0 if os.geteuid() == 0 else os.geteuid()
    if os.geteuid() == 0:
        import grp
        service_gid = grp.getgrnam("siyue-crm-java").gr_gid
    else:
        service_gid = os.getegid()
    staged_tls = stage_tls_files(approval, tls_sources, base, service_uid, service_gid)
    safe_write(unit_path, unit_template.read_bytes(), 0o644)
    analyze = run(["systemd-analyze", "verify", str(unit_path)],
                  capture_output=True, text=True, check=False)
    require(analyze.returncode == 0, "SYSTEMD_UNIT_VERIFY")
    result = run(["systemctl", "daemon-reload"], capture_output=True, text=True, check=False)
    require(result.returncode == 0, "SYSTEMD_RELOAD_FAILED")
    marker["state"] = "empty_schema_staged_service_installed_not_started"
    marker["accountNames"] = names
    marker["tlsInstalledPaths"] = {
        "truststore": str(TLS_TRUSTSTORE_DEST),
        "argfile": str(TLS_ARGFILE_DEST),
        "dropin": str(TLS_DROPIN_DEST),
    }
    marker["tlsInstalledSha256"] = staged_tls
    marker_tmp = marker_path.with_suffix(".json.tmp")
    marker_tmp.write_text(json.dumps(marker, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    marker_tmp.chmod(0o400)
    os.replace(marker_tmp, marker_path)
    return {"schema": schema, "serviceInstalled": True, "serviceStarted": False,
            "serviceEnabled": False, "routeChanged": False, "databaseGrantsApplied": False,
            "state": marker["state"]}


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--approval", type=Path, required=True)
    parser.add_argument("--approval-sha256", required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--admin-cnf", type=Path, required=True)
    parser.add_argument("--secret-input", type=Path, required=True)
    parser.add_argument("--execute", action="store_true", help="perform only empty-schema/account/service staging")
    args = parser.parse_args(argv)
    approval = private_json(args.approval)
    validate_approval(approval, args.approval_sha256)
    require(digest_file(args.jar) == approval["jarSha256"], "JAR_HASH_MISMATCH")
    secret_input = private_json(args.secret_input)
    validate_secret_input(secret_input)
    validate_target_available(approval, args.jar, args.admin_cnf, subprocess.run)
    if not args.execute:
        sys.stdout.write(json.dumps(plan_summary(approval), sort_keys=True) + "\n")
        return 0
    result = apply_provision(approval, args.jar, UNIT_TEMPLATE, args.admin_cnf, secret_input, subprocess.run)
    sys.stdout.write(json.dumps(result, sort_keys=True) + "\n")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except (OSError, Refused, subprocess.SubprocessError):
        sys.stderr.write("PROVISION_REFUSED: inspect private stage marker for recovery state\n")
        raise SystemExit(2)
