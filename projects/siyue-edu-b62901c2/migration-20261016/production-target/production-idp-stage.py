#!/usr/bin/env python3
"""Stage a private, review-only production Keycloak bundle; never contacts services."""
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
import sys
from urllib.parse import urlsplit
import zipfile

PROJECT_ROOT = Path(__file__).resolve().parents[2]
THEME_SOURCE = PROJECT_ROOT / "implementation-20261014/deploy/keycloak-theme/themes/siyue"
USER_PROFILE_SOURCE = Path(__file__).with_name("approved-user-profile.json")
PRODUCTION_ROOT = Path("/opt/siyue-keycloak-production")
FORBIDDEN_ROOTS = (
    Path("/opt/siyue-idp-candidate"),
    Path("/opt/siyue-java-release-v104-staged"),
    Path("/var/lib/mysql"),
)
ISSUER = "https://www.siyuejn.com/crm-auth/realms/siyue-production"
HOSTNAME = "https://www.siyuejn.com/crm-auth"
REALM = "siyue-production"
CLIENT = "siyue-crm-browser"
CLIENT_ORIGIN = "https://www.siyuejn.com"
CLIENT_REDIRECT = "https://www.siyuejn.com/crm/"
AUDIENCE = "siyue-api"
KEYCLOAK_VERSION = "26.8.1"
KEYCLOAK_IMAGE = (
    "quay.io/keycloak/keycloak@sha256:"
    "79c623a22abacf3fe6c00a5fb30f5b7843d717b234033b80995891c06116d244"
)
MYSQL_HOST = "127.0.0.1"
MYSQL_PORT = 50571
MYSQL_SERVICE = "mysql.service"
MYSQL_DATADIR = "/var/lib/mysql/"
KEYCLOAK_HTTPS_HOST = "127.0.0.1"
KEYCLOAK_HTTPS_PORT = 18450
MANAGEMENT_HOST = "127.0.0.1"
MANAGEMENT_PORT = 19050
SCHEMA = "siyue_keycloak_prod"
CONTAINER = "siyue-keycloak-production"
IMAGE_TAG = "siyue-keycloak-production:26.8.1"
TLS_READ_GID = 2901
KEYCLOAK_MEMORY_LIMIT = "1280m"
KEYCLOAK_JAVA_OPTS = "-Xms256m -Xmx768m -XX:ActiveProcessorCount=2 -Djava.net.preferIPv4Stack=true"
REALM_IMAGE_PATH = "/opt/keycloak/import/siyue-production-realm.json"
HASH_RE = re.compile(r"[a-f0-9]{64}\Z")
PROVIDER_SERVICE = "META-INF/services/org.keycloak.credential.hash.PasswordHashProviderFactory"
EVENT_LISTENER_SERVICE = "META-INF/services/org.keycloak.events.EventListenerProviderFactory"
PROVIDER_FACTORY = "cn.siyue.security.keycloak.SiyueScryptPasswordHashProviderFactory"
EVENT_LISTENER_FACTORY = "SiyueCredentialChangeEventListenerFactory"
EVENT_LISTENER_FACTORY_CLASS = f"cn.siyue.security.keycloak.{EVENT_LISTENER_FACTORY}"
EVENT_LISTENER_ID = "siyue-credential-change"
JAVA_CALLBACK_PATH = "/api/v2/system/credential-change-events"
JAVA_CALLBACK = f"http://127.0.0.1:8081{JAVA_CALLBACK_PATH}"


class Refused(ValueError):
    """A safe refusal that contains no path contents, credentials, or source data."""


def require(condition: bool, code: str) -> None:
    if not condition:
        raise Refused(code)


def sha256_file(path: Path) -> str:
    require(path.is_absolute(), "PROVIDER_FILE")
    for component in [*reversed(path.parents), path]:
        try:
            require(not stat.S_ISLNK(component.lstat().st_mode), "PROVIDER_PATH_SYMLINK")
        except FileNotFoundError:
            raise Refused("PROVIDER_FILE") from None
    require(path.is_file(), "PROVIDER_FILE")
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def validate_provider(path: Path, expected_sha256: str) -> str:
    require(bool(HASH_RE.fullmatch(expected_sha256)), "PROVIDER_SHA_REQUIRED")
    actual = sha256_file(path)
    require(actual == expected_sha256, "PROVIDER_SHA_MISMATCH")
    try:
        with zipfile.ZipFile(path) as archive:
            archive_names = set(archive.namelist())
            require(PROVIDER_SERVICE in archive_names, "PROVIDER_DESCRIPTOR_MISSING")
            descriptor = archive.read(PROVIDER_SERVICE).decode("utf-8")
            require(EVENT_LISTENER_SERVICE in archive_names, "EVENT_LISTENER_DESCRIPTOR_MISSING")
            event_descriptor = archive.read(EVENT_LISTENER_SERVICE).decode("utf-8")
    except (OSError, UnicodeError, zipfile.BadZipFile):
        raise Refused("PROVIDER_ARCHIVE_INVALID") from None
    except Refused:
        raise
    provider_entries = {line.strip() for line in descriptor.splitlines()
                        if line.strip() and not line.strip().startswith("#")}
    event_entries = {line.strip() for line in event_descriptor.splitlines()
                     if line.strip() and not line.strip().startswith("#")}
    require(PROVIDER_FACTORY in provider_entries, "PROVIDER_FACTORY_MISSING")
    require(EVENT_LISTENER_FACTORY_CLASS in event_entries, "EVENT_LISTENER_FACTORY_MISSING")
    require(PROVIDER_FACTORY.replace(".", "/") + ".class" in archive_names,
            "PROVIDER_FACTORY_CLASS_MISSING")
    require(EVENT_LISTENER_FACTORY_CLASS.replace(".", "/") + ".class" in archive_names,
            "EVENT_LISTENER_FACTORY_CLASS_MISSING")
    return actual


def read_event_config(path: Path) -> tuple[dict[str, str], str]:
    require(path.is_absolute(), "EVENT_CONFIG_PATH")
    for component in [*reversed(path.parents), path]:
        try:
            require(not stat.S_ISLNK(component.lstat().st_mode), "EVENT_CONFIG_SYMLINK")
        except FileNotFoundError:
            raise Refused("EVENT_CONFIG_MISSING") from None
    info = path.stat()
    require(stat.S_ISREG(info.st_mode) and info.st_uid == os.geteuid()
            and stat.S_IMODE(info.st_mode) == 0o600 and info.st_size <= 4096,
            "EVENT_CONFIG_PERMISSIONS")
    raw = path.read_bytes()
    try:
        value = json.loads(raw, object_pairs_hook=_unique_object)
    except (UnicodeError, json.JSONDecodeError, Refused):
        raise Refused("EVENT_CONFIG_INVALID") from None
    require(type(value) is dict and set(value) == {"endpoint", "issuer", "hmacSecret"},
            "EVENT_CONFIG_FIELDS")
    endpoint = value["endpoint"]
    issuer = value["issuer"]
    hmac_secret = value["hmacSecret"]
    require(type(endpoint) is str and endpoint == JAVA_CALLBACK, "EVENT_CALLBACK_MISMATCH")
    require(type(issuer) is str and issuer == ISSUER, "EVENT_ISSUER_MISMATCH")
    require(type(hmac_secret) is str and bool(re.fullmatch(r"[A-Za-z0-9_-]{32,256}", hmac_secret)),
            "EVENT_HMAC_SECRET")
    parsed = urlsplit(endpoint)
    require(parsed.scheme == "http" and parsed.hostname == "127.0.0.1" and parsed.port == 8081
            and parsed.path == JAVA_CALLBACK_PATH and not parsed.username and not parsed.password
            and not parsed.query and not parsed.fragment,
            "EVENT_CALLBACK_BOUNDARY")
    return {"endpoint": endpoint, "issuer": issuer, "hmacSecret": hmac_secret}, hashlib.sha256(raw).hexdigest()


def _unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    output: dict[str, object] = {}
    for key, value in pairs:
        require(key not in output, "EVENT_CONFIG_DUPLICATE_KEY")
        output[key] = value
    return output


def resource_manifest() -> dict[str, object]:
    ports = {
        "mysql": MYSQL_PORT,
        "keycloakHttpsLoopback": KEYCLOAK_HTTPS_PORT,
        "keycloakManagementLoopback": MANAGEMENT_PORT,
        "javaApiLoopback": 8081,
        "previousCandidateKeycloak": 18449,
    }
    require(len(set(ports.values())) == len(ports), "PORT_COLLISION")
    require(KEYCLOAK_HTTPS_HOST == "127.0.0.1" and MANAGEMENT_HOST == "127.0.0.1",
            "NON_LOOPBACK_BIND")
    require(PRODUCTION_ROOT == Path("/opt/siyue-keycloak-production"), "PRODUCTION_ROOT_DRIFT")
    for forbidden in FORBIDDEN_ROOTS:
        require(PRODUCTION_ROOT != forbidden and forbidden not in PRODUCTION_ROOT.parents,
                "FORBIDDEN_ROOT_OVERLAP")
    require(SCHEMA == "siyue_keycloak_prod", "PRODUCTION_SCHEMA_DRIFT")
    return {
        "productionRoot": str(PRODUCTION_ROOT),
        "forbiddenRootPrefixes": [str(item) for item in FORBIDDEN_ROOTS],
        "mysql": {
            "service": MYSQL_SERVICE,
            "host": MYSQL_HOST,
            "port": MYSQL_PORT,
            "datadir": MYSQL_DATADIR,
            "schema": SCHEMA,
            "serverUuidCheck": "must be captured and pinned in a separate approved server preflight",
        },
        "ports": ports,
        "tlsReadGroupGid": TLS_READ_GID,
        "memoryBudgetMiB": {
            "productionKeycloakLimit": 1280,
            "javaServiceLimit": 1536,
            "candidateIdpLimit": 512,
            "mysqlPlanningCeiling": 1536,
            "redisPlanningCeiling": 512,
            "osNginxAndSystem": 1024,
            "freeHeadroom": 768,
            "hostTotal": 7168,
        },
        "listenerChecks": {
            "keycloakHttps": f"{KEYCLOAK_HTTPS_HOST}:{KEYCLOAK_HTTPS_PORT}",
            "management": f"{MANAGEMENT_HOST}:{MANAGEMENT_PORT}",
            "mustBeVerifiedOnTargetHostBeforeStart": True,
        },
        "retention": {
            "retainProductionRoot": True,
            "allowAutomaticDelete": False,
            "allowCandidateReuse": False,
            "cleanupRequiresSeparateReview": True,
        },
    }


def credential_generation_mapper() -> dict[str, object]:
    return {
        "name": "siyue credential generation",
        "protocol": "openid-connect",
        "protocolMapper": "oidc-usermodel-attribute-mapper",
        "consentRequired": False,
        "config": {
            "user.attribute": "legacy_credential_change_generation",
            "claim.name": "siyue_credential_generation",
            "jsonType.label": "long",
            "access.token.claim": "true",
            "id.token.claim": "false",
            "userinfo.token.claim": "false",
            "multivalued": "false",
        },
    }


def build_realm() -> dict[str, object]:
    return {
        "realm": REALM,
        "enabled": True,
        "displayName": "思跃 CRM",
        "loginTheme": "siyue",
        "internationalizationEnabled": True,
        "defaultLocale": "zh-Hans",
        "supportedLocales": ["zh-Hans"],
        "registrationAllowed": False,
        "resetPasswordAllowed": False,
        "rememberMe": True,
        "bruteForceProtected": True,
        "sslRequired": "external",
        "eventsEnabled": True,
        "eventsListeners": ["jboss-logging", EVENT_LISTENER_ID],
        "clients": [{
            "clientId": CLIENT,
            "name": "Siyue CRM browser",
            "enabled": True,
            "protocol": "openid-connect",
            "publicClient": True,
            "standardFlowEnabled": True,
            "implicitFlowEnabled": False,
            "directAccessGrantsEnabled": False,
            "serviceAccountsEnabled": False,
            "authorizationServicesEnabled": False,
            "redirectUris": [CLIENT_REDIRECT],
            "webOrigins": [CLIENT_ORIGIN],
            "attributes": {
                "pkce.code.challenge.method": "S256",
                "post.logout.redirect.uris": CLIENT_REDIRECT,
            },
            "protocolMappers": [{
                "name": "siyue-api audience",
                "protocol": "openid-connect",
                "protocolMapper": "oidc-audience-mapper",
                "consentRequired": False,
                "config": {
                    "included.custom.audience": AUDIENCE,
                    "access.token.claim": "true",
                    "id.token.claim": "false",
                    "userinfo.token.claim": "false",
                },
            }, credential_generation_mapper()],
        }],
        "roles": {"realm": [], "client": {}},
        "users": [],
    }


def validate_realm(realm: dict[str, object]) -> None:
    require(realm.get("realm") == REALM and realm.get("users") == [], "REALM_OR_USERS")
    require(realm.get("eventsEnabled") is True
            and realm.get("eventsListeners") == ["jboss-logging", EVENT_LISTENER_ID],
            "EVENT_LISTENER_NOT_ENABLED")
    clients = realm.get("clients")
    require(type(clients) is list and len(clients) == 1 and type(clients[0]) is dict,
            "CLIENT_SHAPE")
    client = clients[0]
    require(client.get("clientId") == CLIENT and client.get("publicClient") is True,
            "CLIENT_ID_OR_TYPE")
    require(client.get("redirectUris") == [CLIENT_REDIRECT]
            and client.get("webOrigins") == [CLIENT_ORIGIN], "CLIENT_ORIGIN")
    require(client.get("standardFlowEnabled") is True
            and client.get("attributes", {}).get("pkce.code.challenge.method") == "S256",
            "PKCE_REQUIRED")
    for field in ("implicitFlowEnabled", "directAccessGrantsEnabled", "serviceAccountsEnabled",
                  "authorizationServicesEnabled"):
        require(client.get(field) is False, "CLIENT_FLOW_FORBIDDEN")
    mappers = client.get("protocolMappers", [])
    generation = [item for item in mappers if isinstance(item, dict)
                  and item.get("config", {}).get("claim.name") == "siyue_credential_generation"]
    require(generation == [credential_generation_mapper()], "CREDENTIAL_GENERATION_MAPPER")
    serialized = json.dumps(realm, ensure_ascii=False, sort_keys=True).lower()
    require("candidate" not in serialized and "migration-v" not in serialized,
            "REALM_CANDIDATE_MARKER")

    def reject_secret_fields(value: object) -> None:
        if isinstance(value, dict):
            forbidden = {"secret", "clientsecret", "password", "credentialdata", "credentials"}
            require(not any(str(key).lower() in forbidden for key in value), "REALM_SECRET_FIELD")
            for child in value.values():
                reject_secret_fields(child)
        elif isinstance(value, list):
            for child in value:
                reject_secret_fields(child)

    reject_secret_fields(realm)


def sql_literal(value: str) -> str:
    require("\x00" not in value and "\n" not in value and "\r" not in value,
            "SQL_LITERAL")
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def sql_plan(db_user: str, db_password: str) -> str:
    require(re.fullmatch(r"siyue_kcp_[a-f0-9]{10}", db_user) is not None, "DB_USER")
    require(re.fullmatch(r"[a-f0-9]{48}", db_password) is not None, "DB_PASSWORD")
    return (
        f"CREATE DATABASE `{SCHEMA}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;\n"
        f"CREATE USER {sql_literal(db_user)}@'127.0.0.1' IDENTIFIED BY {sql_literal(db_password)};\n"
        f"GRANT ALL PRIVILEGES ON `{SCHEMA}`.* TO {sql_literal(db_user)}@'127.0.0.1';\n"
        "-- Deliberately no FLUSH, execution, global grant, or other schema grant.\n"
    )


def env_quote(value: str) -> str:
    require("\n" not in value and "\r" not in value, "ENV_NEWLINE")
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$").replace("`", "\\`") + '"'


def runtime_environment(db_user: str, db_password: str, event_config: dict[str, str]) -> str:
    values = {
        "KC_DB": "mysql",
        "KC_DB_URL": f"jdbc:mysql://127.0.0.1:{MYSQL_PORT}/{SCHEMA}?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&sslMode=REQUIRED",
        "KC_DB_USERNAME": db_user,
        "KC_DB_PASSWORD": db_password,
        "KC_DB_POOL_INITIAL_SIZE": "2",
        "KC_DB_POOL_MIN_SIZE": "2",
        "KC_DB_POOL_MAX_SIZE": "30",
        "KC_HTTP_ENABLED": "false",
        "KC_HTTP_HOST": KEYCLOAK_HTTPS_HOST,
        "KC_HTTPS_PORT": str(KEYCLOAK_HTTPS_PORT),
        "KC_HTTPS_CERTIFICATE_FILE": str(PRODUCTION_ROOT / "tls/server.crt"),
        "KC_HTTPS_CERTIFICATE_KEY_FILE": str(PRODUCTION_ROOT / "tls/server.key"),
        "KC_HTTP_RELATIVE_PATH": "/crm-auth",
        "KC_HOSTNAME": HOSTNAME,
        "KC_HOSTNAME_STRICT": "true",
        "KC_PROXY_HEADERS": "xforwarded",
        "KC_PROXY_TRUSTED_ADDRESSES": "127.0.0.1/32",
        "KC_HTTP_MANAGEMENT_HOST": MANAGEMENT_HOST,
        "KC_HTTP_MANAGEMENT_PORT": str(MANAGEMENT_PORT),
        "KC_HEALTH_ENABLED": "false",
        "KC_METRICS_ENABLED": "false",
        "KC_SPI_EVENTS_LISTENER__SIYUE_CREDENTIAL_CHANGE__ENDPOINT": event_config["endpoint"],
        "KC_SPI_EVENTS_LISTENER__SIYUE_CREDENTIAL_CHANGE__ISSUER": event_config["issuer"],
        "KC_SPI_EVENTS_LISTENER__SIYUE_CREDENTIAL_CHANGE__HMAC_SECRET": event_config["hmacSecret"],
        "JAVA_OPTS_APPEND": KEYCLOAK_JAVA_OPTS,
    }
    return "".join(f"{key}={env_quote(values[key])}\n" for key in sorted(values))


def bootstrap_environment(admin_user: str, admin_password: str) -> str:
    return "".join((
        f"KC_BOOTSTRAP_ADMIN_USERNAME={env_quote(admin_user)}\n",
        f"KC_BOOTSTRAP_ADMIN_PASSWORD={env_quote(admin_password)}\n",
    ))


def java_event_environment(event_config: dict[str, str]) -> str:
    values = {
        "SIYUE_CREDENTIAL_CHANGE_ENABLED": "true",
        "SIYUE_CREDENTIAL_CHANGE_ISSUER": event_config["issuer"],
        "SIYUE_CREDENTIAL_CHANGE_HMAC_SECRET": event_config["hmacSecret"],
        "SIYUE_CREDENTIAL_CHANGE_MAX_CLOCK_SKEW_SECONDS": "60",
    }
    return "".join(f"{key}={env_quote(values[key])}\n" for key in sorted(values))


def nginx_review_config() -> str:
    upstream = "https://127.0.0.1:18450"
    common = (
        f"        proxy_pass {upstream};\n"
        "        proxy_http_version 1.1;\n"
        "        proxy_set_header Host localhost;\n"
        "        proxy_set_header X-Forwarded-Host $host;\n"
        "        proxy_set_header X-Forwarded-Proto $scheme;\n"
        "        proxy_set_header X-Forwarded-Port $server_port;\n"
        "        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n"
        "        proxy_set_header X-Forwarded-Prefix /crm-auth;\n"
        "        proxy_ssl_server_name on;\n"
        "        proxy_ssl_name localhost;\n"
        "        proxy_ssl_verify on;\n"
        "        proxy_ssl_verify_depth 2;\n"
        f"        proxy_ssl_trusted_certificate {PRODUCTION_ROOT / 'tls/upstream-ca.crt'};\n"
    )
    return (
        "# Review-only example. Do not install without independent Nginx review.\n"
        "location = /crm-auth/admin { return 404; }\n"
        "location ^~ /crm-auth/admin/ { return 404; }\n"
        "location ^~ /crm-auth/realms/master/ { return 404; }\n"
        "location ^~ /crm-auth/realms/ {\n"
        + common + "}\n"
        "location ^~ /crm-auth/resources/ {\n"
        + common + "}\n"
    )


def dockerfile() -> str:
    return (
        f"FROM {KEYCLOAK_IMAGE}\n"
        "COPY --chown=1000:0 provider.jar /opt/keycloak/providers/siyue-password-hash-provider.jar\n"
        "COPY --chown=1000:0 theme/ /opt/keycloak/themes/siyue/\n"
        "RUN mkdir -p /opt/keycloak/import && chown 1000:0 /opt/keycloak/import && chmod 0750 /opt/keycloak/import\n"
        f"COPY --chown=1000:0 --chmod=0600 realm-import.json {REALM_IMAGE_PATH}\n"
        "RUN /opt/keycloak/bin/kc.sh build --db=mysql --http-relative-path=/crm-auth --health-enabled=false --metrics-enabled=false\n"
    )


def compose(bootstrap: bool) -> str:
    suffix = "bootstrap" if bootstrap else "runtime"
    env_files = ["runtime.env"]
    quoted_env = ", ".join(f'"./{name}"' for name in env_files)
    content = (
        f"name: siyue-keycloak-production-{suffix}\n"
        "services:\n"
    )
    if bootstrap:
        bootstrap_env = '["./runtime.env", "./bootstrap.env"]'
        bootstrap_command = [
            "bootstrap-admin", "user", "--username:env", "KC_BOOTSTRAP_ADMIN_USERNAME",
            "--password:env", "KC_BOOTSTRAP_ADMIN_PASSWORD", "--no-prompt", "--optimized",
        ]
        quoted_bootstrap_command = ", ".join(json.dumps(item) for item in bootstrap_command)
        import_command = ["import", "--file", REALM_IMAGE_PATH, "--optimized", "--override", "false"]
        quoted_import_command = ", ".join(json.dumps(item) for item in import_command)
        content += (
            "  bootstrap-admin:\n"
            f"    image: {IMAGE_TAG}\n"
            "    network_mode: host\n"
            '    entrypoint: ["/opt/keycloak/bin/kc.sh"]\n'
            f"    command: [{quoted_bootstrap_command}]\n"
            f"    env_file: {bootstrap_env}\n"
            '    restart: "no"\n'
            '    user: "1000:0"\n'
            f"    mem_limit: {KEYCLOAK_MEMORY_LIMIT}\n"
            "    cpus: 2.0\n"
            "    pids_limit: 256\n"
            f"    group_add: [\"{TLS_READ_GID}\"]\n"
            "    read_only: true\n"
            "    cap_drop: [ALL]\n"
            "    security_opt: [no-new-privileges:true]\n"
            "    volumes:\n"
            f"      - {PRODUCTION_ROOT / 'tls'}:/opt/siyue-keycloak-production/tls:ro\n"
            "    tmpfs:\n"
            "      - /tmp:rw,noexec,nosuid,size=128m\n"
            "      - /opt/keycloak/data:rw,noexec,nosuid,size=256m,uid=1000,gid=0\n"
            "  realm-import:\n"
            f"    image: {IMAGE_TAG}\n"
            "    network_mode: host\n"
            '    entrypoint: ["/opt/keycloak/bin/kc.sh"]\n'
            f"    command: [{quoted_import_command}]\n"
            '    env_file: ["./runtime.env"]\n'
            '    restart: "no"\n'
            '    user: "1000:0"\n'
            f"    mem_limit: {KEYCLOAK_MEMORY_LIMIT}\n"
            "    cpus: 2.0\n"
            "    pids_limit: 256\n"
            f"    group_add: [\"{TLS_READ_GID}\"]\n"
            "    read_only: true\n"
            "    cap_drop: [ALL]\n"
            "    security_opt: [no-new-privileges:true]\n"
            "    volumes:\n"
            f"      - {PRODUCTION_ROOT / 'tls'}:/opt/siyue-keycloak-production/tls:ro\n"
            "    tmpfs:\n"
            "      - /tmp:rw,noexec,nosuid,size=128m\n"
            "      - /opt/keycloak/data:rw,noexec,nosuid,size=256m,uid=1000,gid=0\n"
            "    depends_on:\n"
            "      bootstrap-admin:\n"
            "        condition: service_completed_successfully\n"
        )
    if not bootstrap:
        content += (
            "  keycloak:\n"
            f"    image: {IMAGE_TAG}\n"
            f"    container_name: {CONTAINER}\n"
            "    network_mode: host\n"
            '    entrypoint: ["/opt/keycloak/bin/kc.sh"]\n'
            '    command: ["start", "--optimized"]\n'
            f"    env_file: [{quoted_env}]\n"
            '    restart: "unless-stopped"\n'
            "    user: \"1000:0\"\n"
            f"    mem_limit: {KEYCLOAK_MEMORY_LIMIT}\n"
            "    cpus: 2.0\n"
            "    pids_limit: 256\n"
            f"    group_add: [\"{TLS_READ_GID}\"]\n"
            "    read_only: true\n"
            "    cap_drop: [ALL]\n"
            "    security_opt: [no-new-privileges:true]\n"
            "    volumes:\n"
            f"      - {PRODUCTION_ROOT / 'tls'}:/opt/siyue-keycloak-production/tls:ro\n"
            "    tmpfs:\n"
            "      - /tmp:rw,noexec,nosuid,size=128m\n"
            "      - /opt/keycloak/data:rw,noexec,nosuid,size=256m,uid=1000,gid=0\n"
        )
    return content


def validate_stage_dir(path: Path) -> Path:
    require(path.is_absolute() and ".." not in path.parts, "STAGE_PATH")
    protected = (PRODUCTION_ROOT, *FORBIDDEN_ROOTS)
    for protected_path in protected:
        require(path != protected_path and protected_path not in path.parents
                and path not in protected_path.parents, "STAGE_PROTECTED_PATH")
    require(not path.exists() and not path.is_symlink(), "STAGE_EXISTS")
    for parent in path.parents:
        if parent.exists():
            require(not stat.S_ISLNK(parent.lstat().st_mode), "STAGE_PARENT_SYMLINK")
    return path


def write_file(path: Path, payload: bytes | str, mode: int = 0o600) -> None:
    data = payload.encode("utf-8") if isinstance(payload, str) else payload
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), mode)
    with os.fdopen(fd, "wb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    os.chmod(path, mode)


def copy_file(source: Path, target: Path) -> None:
    """Copy a staged file using exclusive creation and a private source mode."""
    write_file(target, source.read_bytes(), 0o600)


def copy_theme(source: Path, target: Path) -> None:
    require(source.is_dir() and not source.is_symlink(), "THEME_SOURCE")
    for path in sorted(source.rglob("*")):
        require(not path.is_symlink(), "THEME_SYMLINK")
        relative = path.relative_to(source)
        destination = target / relative
        if path.is_dir():
            destination.mkdir(mode=0o700)
        elif path.is_file():
            write_file(destination, path.read_bytes())
        else:
            raise Refused("THEME_SPECIAL_FILE")


def stage_bundle(stage_dir: Path, provider: Path, provider_sha256: str, event_config_path: Path,
                 theme_source: Path = THEME_SOURCE) -> dict[str, object]:
    validate_stage_dir(stage_dir)
    provider_hash = validate_provider(provider, provider_sha256)
    event_config, event_config_hash = read_event_config(event_config_path)
    resources = resource_manifest()
    realm = build_realm()
    validate_realm(realm)
    db_user = "siyue_kcp_" + secrets.token_hex(5)
    db_password = secrets.token_hex(24)
    admin_user = "kc-bootstrap-" + secrets.token_hex(4)
    admin_password = secrets.token_hex(24)

    stage_dir.parent.mkdir(parents=True, exist_ok=True)
    stage_dir.mkdir(mode=0o700)
    os.chmod(stage_dir, 0o700)
    tls_dir = stage_dir / "tls"
    tls_dir.mkdir(mode=0o750)
    build_dir = stage_dir / "build"
    build_dir.mkdir(mode=0o700)
    (build_dir / "theme").mkdir(mode=0o700)
    copy_theme(theme_source, build_dir / "theme")
    write_file(build_dir / "provider.jar", provider.read_bytes())
    write_file(build_dir / "Dockerfile", dockerfile())

    write_file(stage_dir / "realm-import.json", json.dumps(realm, ensure_ascii=False, indent=2) + "\n")
    write_file(stage_dir / "mysql-create.sql", sql_plan(db_user, db_password))
    write_file(stage_dir / "runtime.env", runtime_environment(db_user, db_password, event_config))
    write_file(stage_dir / "java-credential-change.env", java_event_environment(event_config))
    write_file(stage_dir / "bootstrap.env", bootstrap_environment(admin_user, admin_password))
    write_file(stage_dir / "compose.bootstrap.yaml", compose(True))
    write_file(stage_dir / "compose.runtime.yaml", compose(False))
    write_file(stage_dir / "initialize-keycloak.sh", (
        "#!/bin/sh\n"
        "set -eu\n"
        "cd -- \"$(dirname -- \"$0\")\"\n"
        "docker compose -p siyue-keycloak-production-bootstrap -f compose.bootstrap.yaml run --rm --no-deps bootstrap-admin\n"
        "docker compose -p siyue-keycloak-production-bootstrap -f compose.bootstrap.yaml run --rm --no-deps realm-import\n"
    ), 0o700)
    write_file(stage_dir / "nginx-review.conf", nginx_review_config())
    write_file(stage_dir / "tls/README.txt", (
        "TLS certificate and private key are intentionally not generated here.\n"
        f"On the target host create the dedicated group with GID {TLS_READ_GID} after\n"
        "checking that GID is unused. Set this directory root:<that-group> mode 0750;\n"
        "set server.key root:<that-group> mode 0640 and server.crt/upstream-ca.crt\n"
        "root-owned mode 0644. Compose adds that group to the default Keycloak UID 1000.\n"
        "The proxy uses Host/SNI localhost, so server.crt needs DNS SAN localhost and\n"
        "must chain to upstream-ca.crt. The public issuer remains www.siyuejn.com.\n"
    ))
    copy_file(stage_dir / "realm-import.json", build_dir / "realm-import.json")
    copy_file(USER_PROFILE_SOURCE, stage_dir / "approved-user-profile.json")
    marker = {
        "kind": "production-keycloak-retained-stage-v1",
        "status": "staged-review-only-not-deployed",
        "productionRetention": resources["retention"],
        "resourceManifest": resources,
        "realm": REALM,
        "client": CLIENT,
        "issuer": ISSUER,
        "databaseSchema": SCHEMA,
        "databasePort": MYSQL_PORT,
        "keycloakVersion": KEYCLOAK_VERSION,
        "officialImage": KEYCLOAK_IMAGE,
        "providerSha256": provider_hash,
        "providerShaWasExplicitArgument": True,
        "credentialEventConfigSha256": event_config_hash,
        "credentialEventEndpoint": event_config["endpoint"],
        "credentialEventIssuer": event_config["issuer"],
        "credentialEventHmacConfigured": True,
        "realmEventListenerEnabled": EVENT_LISTENER_ID in realm["eventsListeners"],
        "bootstrapSequence": ["bootstrap-admin-user", "realm-import", "runtime-start"],
        "bootstrapUsesDedicatedCommand": True,
        "userProfileApplied": False,
        "requiredUnmanagedAttributePolicy": "ADMIN_EDIT",
        "containsProductionAccounts": False,
        "containsCandidateData": False,
        "databaseWasCreated": False,
        "imageWasBuilt": False,
        "serviceWasStarted": False,
        "nginxWasModified": False,
        "providerMustBeReviewedBeforeExecution": True,
        "tlsCertificateStatus": "not-provisioned",
        "serverPreflightStatus": "required-before-any-execution",
    }
    write_file(stage_dir / "production-retention.json", json.dumps(marker, indent=2) + "\n")
    write_file(stage_dir / "STAGE-ONLY-NOT-DEPLOYED.txt", (
        "Review package only. No database, image, service, realm, or route was changed.\n"
        "Do not delete this production root automatically after deployment.\n"
    ))
    return {
        "status": marker["status"],
        "productionRoot": str(PRODUCTION_ROOT),
        "databaseSchema": SCHEMA,
        "mysqlPort": MYSQL_PORT,
        "keycloakPort": KEYCLOAK_HTTPS_PORT,
        "realm": REALM,
        "client": CLIENT,
        "providerSha256": provider_hash,
        "credentialEventConfigSha256": event_config_hash,
        "realmEventListenerEnabled": True,
        "databaseCreated": False,
        "serviceStarted": False,
        "nginxModified": False,
        "secretValuesPrinted": False,
    }


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage-dir", type=Path, required=True,
                        help="new private review directory; no system changes are made")
    parser.add_argument("--provider", type=Path, required=True,
                        help="the separately reviewed provider JAR; never defaults to an older build")
    parser.add_argument("--provider-sha256", required=True,
                        help="explicit SHA-256 supplied by the provider reviewer")
    parser.add_argument("--credential-event-config", type=Path, required=True,
                        help="0600 JSON shared input for endpoint, issuer and Java/Keycloak HMAC")
    args = parser.parse_args(argv)
    try:
        summary = stage_bundle(args.stage_dir, args.provider, args.provider_sha256, args.credential_event_config)
    except (OSError, Refused, ValueError) as exc:
        code = str(exc) if isinstance(exc, Refused) else type(exc).__name__
        sys.stderr.write(f"PRODUCTION_IDP_STAGE_REFUSED: {code}\n")
        return 2
    sys.stdout.write(json.dumps(summary, ensure_ascii=False) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(os.sys.argv[1:]))
