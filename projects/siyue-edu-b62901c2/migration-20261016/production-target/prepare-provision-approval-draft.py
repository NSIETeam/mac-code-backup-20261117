#!/usr/bin/env python3
"""Build a root-only Java provision approval draft from live verified artifacts.

This tool does not freeze source data, write an executable approval, create a
schema/account, or install/start a service. It records only facts that are
available before the final maintenance freeze and marks all freeze-dependent
fields as pending.
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import stat
import ssl
import subprocess
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


MYSQL_ADMIN = Path("/opt/siyue-java-real-candidate/v101/mysql-admin.cnf")
MYSQL_PORT = 50571
MYSQL_SERVICE = "mysql.service"
MYSQL_DATADIR = "/var/lib/mysql/"
JAR = Path("/opt/siyue-java-build-v107-final/backend-java/target/siyue-core-0.1.0.jar")
REALM_SPEC = Path("/opt/siyue-keycloak-production/realm-import.json")
PROVIDER = Path("/opt/siyue-keycloak-production/build/provider.jar")
REWARD_KEY = Path("/opt/siyue-java-real-candidate/v101/source-input/reward-key.env")
SECRET_INPUT = Path("/etc/siyue-crm/provisioning/secret-input.json")
REDIS_CREDENTIALS = Path("/etc/siyue-redis-production/redis-credentials.json")
REDIS_CLI = Path("/opt/siyue-redis-production/7.2.16/redis-cli")
IDP_ROOT = Path("/opt/siyue-keycloak-production")
IDP_BASE = "https://localhost:18450/crm-auth"
IDP_REALM = "siyue-production"
IDP_ISSUER = "https://www.siyuejn.com/crm-auth/realms/siyue-production"
IDP_CLIENT = "siyue-crm-browser"
IDP_RESET_CLIENT = "siyue-java-admin-reset"
TLS_ROOT = Path("/opt/siyue-keycloak-production-prep-20261017")
HASH_RE = re.compile(r"[a-f0-9]{64}\Z")
SCHEMA_PREFIX = "siyue_migration_candidate_"
MYSQL_VERSION_RE = re.compile(r"8\.0\.46(?:-[A-Za-z0-9.+_~-]+)?\Z")


class Refused(ValueError):
    """A non-sensitive fixed refusal code."""


def require(condition: bool, code: str) -> None:
    if not condition:
        raise Refused(code)


def sha256_file(path: Path) -> str:
    require(path.is_file() and not path.is_symlink(), "ARTIFACT_FILE")
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def root_file(path: Path, mode: int | None = None, minimum_size: int = 1) -> None:
    require(path.is_absolute() and not path.is_symlink(), "FILE_PATH")
    info = path.stat()
    require(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and info.st_size >= minimum_size,
            "FILE_OWNER_OR_SIZE")
    if mode is not None:
        require(stat.S_IMODE(info.st_mode) == mode, "FILE_MODE")


def read_unique_json(path: Path) -> dict[str, object]:
    root_file(path, 0o600)
    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)
    except (OSError, UnicodeError, json.JSONDecodeError):
        raise Refused("PRIVATE_JSON_INVALID") from None
    require(type(value) is dict, "PRIVATE_JSON_SHAPE")
    return value


def unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        require(key not in result, "DUPLICATE_JSON_KEY")
        result[key] = value
    return result


def read_env(path: Path) -> dict[str, str]:
    root_file(path, 0o600)
    output: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.lstrip().startswith("#"):
            continue
        key, sep, value = line.partition("=")
        require(bool(sep) and re.fullmatch(r"[A-Z0-9_]+", key) is not None, "ENV_FORMAT")
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        require(key not in output and "\n" not in value and "\r" not in value, "ENV_DUPLICATE")
        output[key] = value
    return output


def run(command: list[str], timeout: int = 15) -> str:
    result = subprocess.run(command, text=True, capture_output=True, check=False, timeout=timeout)
    require(result.returncode == 0, "CHECK_FAILED_" + Path(command[0]).name.upper())
    return result.stdout.strip()


def mysql_query(query: str) -> str:
    root_file(MYSQL_ADMIN, 0o600)
    return run(["/usr/bin/mysql", f"--defaults-extra-file={MYSQL_ADMIN}", "--protocol=TCP",
                "-h127.0.0.1", f"-P{MYSQL_PORT}", "--ssl-mode=REQUIRED", "--batch",
                "--skip-column-names", "-e", query])


def mysql_target() -> tuple[str, str]:
    state_output = run(["/usr/bin/systemctl", "show", MYSQL_SERVICE,
                        "--property=LoadState,ActiveState,SubState"])
    state = {}
    for line in state_output.splitlines():
        key, sep, value = line.partition("=")
        if sep and key in {"LoadState", "ActiveState", "SubState"}:
            require(key not in state, "MYSQL_STATE_DUPLICATE")
            state[key] = value
    require(state == {"LoadState": "loaded", "ActiveState": "active", "SubState": "running"},
            "MYSQL_SERVICE_STATE")
    fields = mysql_query("SELECT @@server_uuid,@@port,@@datadir,@@log_bin,"
                         "@@log_bin_trust_function_creators,VERSION(),@@have_ssl;").split("\t")
    require(len(fields) == 7 and fields[1] == str(MYSQL_PORT) and fields[2] == MYSQL_DATADIR
            and fields[3].lower() == "1" and fields[4] in {"0", "OFF"}
            and MYSQL_VERSION_RE.fullmatch(fields[5]) is not None
            and fields[6].upper() in {"YES", "ON", "1"},
            "MYSQL_INSTANCE")
    return fields[0], fields[2]


def new_available_schema() -> str:
    for _ in range(20):
        schema = SCHEMA_PREFIX + secrets.token_hex(6)
        count = mysql_query("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='"
                            + schema + "';")
        require(count == "0", "SCHEMA_COLLISION_RETRY")
        # The provisioner derives all three accounts from this schema name.
        token = schema.rsplit("_", 1)[1]
        names = ["sy_rt_" + token, "sy_mig_" + token, "sy_view_" + token]
        sql_names = ",".join("'" + name.replace("'", "''") + "'" for name in names)
        require(mysql_query("SELECT COUNT(*) FROM mysql.user WHERE Host='127.0.0.1' AND User IN ("
                            + sql_names + ");") == "0", "DB_ACCOUNT_COLLISION")
        return schema
    raise Refused("SCHEMA_ALLOCATION_FAILED")


def idp_request(context: ssl.SSLContext, url: str, token: str | None = None,
                form: dict[str, str] | None = None) -> object:
    headers = {"Accept": "application/json"}
    body = None
    if form is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded"
        body = urlencode(form).encode()
    if token:
        headers["Authorization"] = "Bearer " + token
    request = Request(url, data=body, headers=headers)
    try:
        with urlopen(request, context=context, timeout=8) as response:
            payload = response.read(2 * 1024 * 1024 + 1)
            require(len(payload) <= 2 * 1024 * 1024, "IDP_RESPONSE_SIZE")
            if response.status == 204 or not payload:
                return None
            return json.loads(payload)
    except (HTTPError, URLError, TimeoutError, OSError, json.JSONDecodeError):
        raise Refused("IDP_READBACK_FAILED") from None


def verify_idp(secret_input: dict[str, object]) -> dict[str, bool]:
    bootstrap = read_env(IDP_ROOT / "bootstrap.env")
    runtime = read_env(IDP_ROOT / "runtime.env")
    java_event = read_env(IDP_ROOT / "java-credential-change.env")
    admin_user = bootstrap.get("KC_BOOTSTRAP_ADMIN_USERNAME", "")
    admin_password = bootstrap.get("KC_BOOTSTRAP_ADMIN_PASSWORD", "")
    require(bool(admin_user) and bool(admin_password), "IDP_BOOTSTRAP_CREDENTIALS")
    ctx = ssl.create_default_context(cafile=str(IDP_ROOT / "tls/upstream-ca.crt"))
    ctx.check_hostname = True
    token_endpoint = IDP_BASE + "/realms/master/protocol/openid-connect/token"
    token_result = idp_request(ctx, token_endpoint, form={"grant_type": "password",
        "client_id": "admin-cli", "username": admin_user, "password": admin_password})
    require(type(token_result) is dict and isinstance(token_result.get("access_token"), str),
            "IDP_ADMIN_AUTH")
    bearer = token_result["access_token"]
    realm = idp_request(ctx, IDP_BASE + "/admin/realms/" + IDP_REALM, bearer)
    require(type(realm) is dict, "IDP_REALM_READBACK")
    require(realm.get("realm") == IDP_REALM and realm.get("enabled") is True
            and realm.get("loginTheme") == "siyue"
            and realm.get("defaultLocale") == "zh-Hans"
            and "zh-Hans" in realm.get("supportedLocales", [])
            and realm.get("eventsEnabled") is True
            and "siyue-credential-change" in realm.get("eventsListeners", []),
            "IDP_REALM_CONTRACT")
    desired_profile = read_unique_json(IDP_ROOT / "approved-user-profile.json")
    actual_profile = idp_request(ctx, IDP_BASE + "/admin/realms/" + IDP_REALM
                                 + "/users/profile", bearer)
    require(type(actual_profile) is dict
            and actual_profile.get("unmanagedAttributePolicy")
            == desired_profile.get("unmanagedAttributePolicy"), "IDP_PROFILE_POLICY")

    def profile_projection(profile: dict[str, object]) -> dict[str, object]:
        attributes = profile.get("attributes")
        require(type(attributes) is list, "IDP_PROFILE_ATTRIBUTES")
        projected = {}
        for item in attributes:
            require(type(item) is dict and isinstance(item.get("name"), str), "IDP_PROFILE_ATTRIBUTE")
            name = item["name"]
            require(name not in projected, "IDP_PROFILE_ATTRIBUTE_DUPLICATE")
            projected[name] = {key: item[key] for key in
                               ("displayName", "validations", "permissions", "multivalued") if key in item}
        return projected

    require(profile_projection(actual_profile) == profile_projection(desired_profile),
            "IDP_PROFILE_MISMATCH")
    clients = idp_request(ctx, IDP_BASE + "/admin/realms/" + IDP_REALM
                          + "/clients?clientId=" + IDP_CLIENT, bearer)
    require(type(clients) is list and len(clients) == 1, "IDP_BROWSER_CLIENT_LOOKUP")
    browser = clients[0]
    require(browser.get("clientId") == IDP_CLIENT and browser.get("enabled") is True
            and browser.get("publicClient") is True and browser.get("standardFlowEnabled") is True
            and browser.get("implicitFlowEnabled") is False
            and browser.get("directAccessGrantsEnabled") is False
            and browser.get("serviceAccountsEnabled") is False
            and browser.get("redirectUris") == ["https://www.siyuejn.com/crm/"]
            and browser.get("webOrigins") == ["https://www.siyuejn.com"], "IDP_BROWSER_CLIENT_CONTRACT")
    mappers = idp_request(ctx, IDP_BASE + "/admin/realms/" + IDP_REALM + "/clients/"
                          + str(browser["id"]) + "/protocol-mappers/models", bearer)
    generation_mappers = [mapper for mapper in mappers if mapper.get("name") == "siyue credential generation"]
    require(len(generation_mappers) == 1, "IDP_GENERATION_MAPPER")
    mapper_config = generation_mappers[0].get("config", {})
    require(mapper_config.get("user.attribute") == "legacy_credential_change_generation"
            and mapper_config.get("claim.name") == "siyue_credential_generation"
            and mapper_config.get("jsonType.label") == "long"
            and mapper_config.get("access.token.claim") == "true"
            and mapper_config.get("id.token.claim") == "false"
            and mapper_config.get("userinfo.token.claim") == "false", "IDP_GENERATION_MAPPER_CONTRACT")
    reset_clients = idp_request(ctx, IDP_BASE + "/admin/realms/" + IDP_REALM
                                + "/clients?clientId=" + IDP_RESET_CLIENT, bearer)
    require(type(reset_clients) is list and len(reset_clients) == 1, "IDP_RESET_CLIENT_LOOKUP")
    reset_client = reset_clients[0]
    require(reset_client.get("clientId") == IDP_RESET_CLIENT and reset_client.get("enabled") is True
            and reset_client.get("publicClient") is False and reset_client.get("serviceAccountsEnabled") is True
            and reset_client.get("standardFlowEnabled") is False
            and reset_client.get("directAccessGrantsEnabled") is False, "IDP_RESET_CLIENT_CONTRACT")
    reset_secret = idp_request(ctx, IDP_BASE + "/admin/realms/" + IDP_REALM + "/clients/"
                               + str(reset_client["id"]) + "/client-secret", bearer)
    require(type(reset_secret) is dict and isinstance(reset_secret.get("value"), str)
            and hmac.compare_digest(reset_secret["value"], str(secret_input["credentialAdminResetClientSecret"])),
            "IDP_RESET_SECRET_MISMATCH")
    service_user = idp_request(ctx, IDP_BASE + "/admin/realms/" + IDP_REALM + "/clients/"
                               + str(reset_client["id"]) + "/service-account-user", bearer)
    require(type(service_user) is dict and isinstance(service_user.get("id"), str),
            "IDP_RESET_SERVICE_ACCOUNT")
    management_clients = idp_request(ctx, IDP_BASE + "/admin/realms/" + IDP_REALM
                                     + "/clients?clientId=realm-management", bearer)
    require(type(management_clients) is list and len(management_clients) == 1
            and isinstance(management_clients[0].get("id"), str), "IDP_REALM_MANAGEMENT_CLIENT")
    management_id = management_clients[0]["id"]
    mappings_path = IDP_BASE + "/admin/realms/" + IDP_REALM + "/users/" + service_user["id"]
    mappings_path += "/role-mappings/clients/" + management_id
    direct_roles = idp_request(ctx, mappings_path, bearer)
    require(type(direct_roles) is list and
            {item.get("name") for item in direct_roles if type(item) is dict}
            == {"manage-users", "view-users"}, "IDP_RESET_DIRECT_ROLES")
    effective_roles = idp_request(ctx, mappings_path + "/composite", bearer)
    effective_names = {item.get("name") for item in effective_roles if type(item) is dict}
    forbidden_roles = {"realm-admin", "manage-realm", "manage-clients", "view-clients",
                       "manage-authorization", "manage-events"}
    require({"manage-users", "view-users"}.issubset(effective_names)
            and not effective_names.intersection(forbidden_roles), "IDP_RESET_EFFECTIVE_ROLES")
    realm_roles = idp_request(ctx, IDP_BASE + "/admin/realms/" + IDP_REALM + "/users/"
                              + service_user["id"] + "/role-mappings/realm/composite", bearer)
    require(type(realm_roles) is list
            and not {item.get("name") for item in realm_roles if type(item) is dict}
            .intersection(forbidden_roles), "IDP_RESET_REALM_ROLES")
    listener_hmac = runtime.get("KC_SPI_EVENTS_LISTENER__SIYUE_CREDENTIAL_CHANGE__HMAC_SECRET", "")
    java_hmac = java_event.get("SIYUE_CREDENTIAL_CHANGE_HMAC_SECRET", "")
    input_hmac = secret_input.get("credentialChangeHmacSecret", "")
    require(all(isinstance(item, str) and len(item) >= 32 for item in (listener_hmac, java_hmac, input_hmac))
            and hmac.compare_digest(listener_hmac, java_hmac)
            and hmac.compare_digest(listener_hmac, input_hmac), "IDP_HMAC_MISMATCH")
    require(runtime.get("KC_SPI_EVENTS_LISTENER__SIYUE_CREDENTIAL_CHANGE__ISSUER") == IDP_ISSUER
            and java_event.get("SIYUE_CREDENTIAL_CHANGE_ISSUER") == IDP_ISSUER
            and java_event.get("SIYUE_CREDENTIAL_CHANGE_ENABLED") == "true",
            "IDP_EVENT_CONFIGURATION")
    return {"realmClientProfile": True, "credentialGenerationMapper": True,
            "adminResetClientSecretMatched": True, "eventHmacMatched": True,
            "eventListenerEnabled": True, "userProfileMatched": True,
            "resetServiceAccountMinimumRoles": True}


def verify_redis(secret_input: dict[str, object]) -> None:
    redis = read_unique_json(REDIS_CREDENTIALS)
    fields = ("redisHost", "redisPort", "redisUsername", "redisPassword", "redisTls")
    require(all(field in redis for field in (*fields, "cacheNamespace", "cacheTtlSeconds")),
            "REDIS_CREDENTIAL_FILE")
    for field in fields:
        left = secret_input[field]
        right = redis[field]
        if field == "redisPassword":
            require(isinstance(left, str) and isinstance(right, str)
                    and hmac.compare_digest(left, right), "REDIS_CREDENTIAL_MISMATCH")
        else:
            require(left == right, "REDIS_CREDENTIAL_MISMATCH")
    require(isinstance(redis["cacheNamespace"], str) and bool(redis["cacheNamespace"])
            and type(redis["cacheTtlSeconds"]) is int and redis["cacheTtlSeconds"] == 300,
            "REDIS_CACHE_CONFIGURATION")
    env = dict(os.environ)
    env["REDISCLI_AUTH"] = str(secret_input["redisPassword"])
    root_file(REDIS_CLI)
    result = subprocess.run([str(REDIS_CLI), "-h", str(secret_input["redisHost"]),
                             "-p", str(secret_input["redisPort"]), "--user",
                             str(secret_input["redisUsername"]), "PING"],
                            text=True, capture_output=True, check=False, timeout=8, env=env)
    require(result.returncode == 0 and result.stdout.strip() == "PONG", "REDIS_AUTHENTICATION")


def load_provisioner(path: Path):
    spec = importlib.util.spec_from_file_location("provision_production_target", path)
    require(spec is not None and spec.loader is not None, "PROVISIONER_MODULE")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def create_draft(args: argparse.Namespace) -> dict[str, object]:
    require(os.geteuid() == 0, "ROOT_REQUIRED")
    provisioner = load_provisioner(args.provisioner)
    schema_uuid, datadir = mysql_target()
    schema = new_available_schema()
    secret_input = read_unique_json(SECRET_INPUT)
    provisioner.validate_secret_input(secret_input)
    secret_names = sorted(secret_input)
    verify_redis(secret_input)
    idp_readback = verify_idp(secret_input)
    reset_review_path = TLS_ROOT / "inputs/reset-client-review.json"
    reset_review = read_unique_json(reset_review_path)
    require(reset_review.get("schema") == "siyue.formal-keycloak-reset-client-review/v1"
            and reset_review.get("realm") == IDP_REALM and reset_review.get("clientId") == IDP_RESET_CLIENT
            and reset_review.get("directRealmManagementRoles") == ["manage-users", "view-users"]
            and reset_review.get("realmAdminOrClientManagementGranted") is False,
            "IDP_RESET_REVIEW_MISMATCH")
    reset_review_hash = sha256_file(reset_review_path)
    root_file(REWARD_KEY, 0o600, 32)

    tls = {
        "tlsCertificateApprovalPath": TLS_ROOT / "inputs/java-tls-approval.json",
        "tlsCaCertificatePath": TLS_ROOT / "tls/formal-ca.crt",
        "tlsLeafCertificatePath": TLS_ROOT / "tls/localhost.crt",
        "tlsTruststorePath": TLS_ROOT / "trust/java/java-truststore.jks",
        "tlsArgfilePath": TLS_ROOT / "trust/java/java-tls.args",
        "tlsDropinPath": TLS_ROOT / "trust/java/20-java-tls-truststore.conf",
        "tlsReviewPath": TLS_ROOT / "trust/java/truststore-review.json",
    }
    for path in tls.values():
        require(path.exists() and not path.is_symlink(), "TLS_ARTIFACT_MISSING")
    cert_approval = read_unique_json(tls["tlsCertificateApprovalPath"])
    cert_approval_hash = hashlib.sha256(json.dumps(cert_approval, sort_keys=True,
        separators=(",", ":")).encode()).hexdigest()
    ca_fp, _ = provisioner.certificate_fingerprint(tls["tlsCaCertificatePath"])
    leaf_fp, _ = provisioner.certificate_fingerprint(tls["tlsLeafCertificatePath"])
    require(ca_fp == cert_approval.get("caSha256") and leaf_fp == cert_approval.get("leafSha256"),
            "TLS_CERTIFICATE_APPROVAL_MISMATCH")
    root_file(args.service_unit)
    service_unit_hash = sha256_file(args.service_unit)
    jar_hash = sha256_file(JAR)
    realm_hash = sha256_file(REALM_SPEC)
    provider_hash = sha256_file(PROVIDER)
    require(jar_hash == "b43bfbd6a780dacfa81cd37c9136ea0ae573dab3843da95832ac1f43f765d470",
            "JAVA_JAR_PIN_MISMATCH")
    require(provider_hash == "3c720ea589f54003af171eaec038b580f769bf1873ac3932a58ce567cb028c7a",
            "IDP_PROVIDER_PIN_MISMATCH")
    tls_fields = {
        "tlsCertificateApprovalPath": str(tls["tlsCertificateApprovalPath"]),
        "tlsCertificateApprovalSha256": cert_approval_hash,
        "tlsCertificateApprovalFileSha256": sha256_file(tls["tlsCertificateApprovalPath"]),
        "tlsCaCertificatePath": str(tls["tlsCaCertificatePath"]),
        "tlsCaCertificateSha256": sha256_file(tls["tlsCaCertificatePath"]),
        "tlsCaFingerprintSha256": ca_fp,
        "tlsLeafCertificatePath": str(tls["tlsLeafCertificatePath"]),
        "tlsLeafCertificateSha256": sha256_file(tls["tlsLeafCertificatePath"]),
        "tlsLeafFingerprintSha256": leaf_fp,
        "tlsIntermediatesPath": None,
        "tlsIntermediatesSha256": None,
        "tlsTruststorePath": str(tls["tlsTruststorePath"]),
        "tlsTruststoreSha256": sha256_file(tls["tlsTruststorePath"]),
        "tlsArgfilePath": str(tls["tlsArgfilePath"]),
        "tlsArgfileSha256": sha256_file(tls["tlsArgfilePath"]),
        "tlsDropinPath": str(tls["tlsDropinPath"]),
        "tlsDropinSha256": sha256_file(tls["tlsDropinPath"]),
        "tlsReviewPath": str(tls["tlsReviewPath"]),
        "tlsReviewSha256": sha256_file(tls["tlsReviewPath"]),
    }
    out_dir = args.output_dir
    require(out_dir.is_absolute() and out_dir.is_dir() and not out_dir.is_symlink(), "OUTPUT_DIRECTORY")
    info = out_dir.stat()
    require(info.st_uid == 0 and stat.S_IMODE(info.st_mode) == 0o700, "OUTPUT_DIRECTORY_PERMISSIONS")
    draft_path = out_dir / f"java-provision-approval-draft-{schema}.json"
    require(not draft_path.exists() and not draft_path.is_symlink(), "OUTPUT_EXISTS")
    document = {
        "format": "siyue.java-provision-approval-draft/v1",
        "status": "static_inputs_verified_awaiting_final_freeze_and_root_approval",
        "approvalExecutable": False,
        "staticApprovalFields": {
            "schema": schema, "serverUuid": schema_uuid, "mysqlPort": MYSQL_PORT,
            "mysqlService": MYSQL_SERVICE, "mysqlDatadir": datadir,
            "rewardKeyPath": str(REWARD_KEY), "jarSha256": jar_hash,
            "jarPath": str(JAR), "keycloakRealmSpecSha256": realm_hash,
            "keycloakRealmSpecPath": str(REALM_SPEC),
            "credentialProviderSha256": provider_hash,
            "credentialProviderPath": str(PROVIDER),
            "resetClientReviewPath": str(reset_review_path),
            "resetClientReviewSha256": reset_review_hash,
            "serviceUnitSha256": service_unit_hash,
            **tls_fields,
        },
        "verified": {
            "targetSchemaAbsent": True,
            "derivedDbAccountsAbsent": True,
            "mysqlServiceAndInstance": True,
            "privateSecretInputFields": secret_names,
            "privateSecretInputValuesPrinted": False,
            "redisCredentialsMatchedAndAuthenticated": True,
            "formalIdpReadback": idp_readback,
            "tlsCertificateAndTruststore": True,
            "jarAndProviderPins": True,
        },
        "pendingFields": ["approvedBy", "freezeId", "sourceRevision",
                          "businessSourceSha256", "authSourceSha256",
                          "businessSourcePath", "authSourcePath"],
        "finalFreezeRequirement": "Use the same-server freeze-complete.json and freeze-manifest.json; verify source.json/auth-source.json hashes and writersStoppedAtFinalCheck before constructing an executable approval.",
        "noProvisioningPerformed": True,
        "noSchemaOrAccountCreated": True,
        "noServiceInstalledOrStarted": True,
        "noProxyRouteChanged": True,
    }
    payload = (json.dumps(document, sort_keys=True, ensure_ascii=False, indent=2) + "\n").encode()
    fd = os.open(draft_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
    except Exception:
        try:
            draft_path.unlink()
        except OSError:
            pass
        raise
    return {"result": "draft_created", "path": str(draft_path), "schema": schema,
            "serverUuid": schema_uuid, "jarSha256": jar_hash, "providerSha256": provider_hash,
            "serviceUnitSha256": service_unit_hash, "idpReadback": idp_readback,
            "pendingFields": document["pendingFields"], "executable": False}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--provisioner", type=Path, required=True)
    parser.add_argument("--service-unit", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, default=Path("/etc/siyue-crm/provisioning"))
    args = parser.parse_args()
    try:
        print(json.dumps(create_draft(args), sort_keys=True))
        return 0
    except Refused as exc:
        print(json.dumps({"result": "blocked", "code": str(exc)}, sort_keys=True), file=sys.stderr)
        return 2
    except Exception as exc:
        # Keep unexpected exception messages/path contents out of operator output.
        print(json.dumps({"result": "blocked", "code": "UNEXPECTED_" + type(exc).__name__},
                         sort_keys=True), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
