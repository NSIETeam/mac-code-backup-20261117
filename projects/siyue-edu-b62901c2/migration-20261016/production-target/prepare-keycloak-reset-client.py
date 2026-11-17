#!/usr/bin/env python3
"""Create or verify the formal realm's least-scope Java reset client.

Only the fixed siyue-production realm is addressable. The service account is
granted the realm-management manage-users role needed for exact user reads,
temporary password reset, and logout. No realm-admin/client-management role is
assigned. All credentials remain in root-only files and process memory.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import ssl
import stat
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


ROOT = Path("/opt/siyue-keycloak-production")
BASE = "https://localhost:18450/crm-auth"
REALM = "siyue-production"
CLIENT_ID = "siyue-java-admin-reset"
ISSUER = "https://www.siyuejn.com/crm-auth/realms/siyue-production"
SECRET_INPUT = Path("/etc/siyue-crm/provisioning/secret-input.json")
REVIEW = Path("/opt/siyue-keycloak-production-prep-20261017/inputs/reset-client-review.json")


class Refused(ValueError):
    """Fixed failure code with no secret values or provider response text."""


def require(condition: bool, code: str) -> None:
    if not condition:
        raise Refused(code)


def read_private_json(path: Path) -> dict[str, object]:
    require(path.is_absolute() and not path.is_symlink(), "PRIVATE_PATH")
    info = path.stat()
    require(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and stat.S_IMODE(info.st_mode) == 0o600,
            "PRIVATE_FILE_PERMISSIONS")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        raise Refused("PRIVATE_FILE_INVALID") from None
    require(type(value) is dict, "PRIVATE_FILE_SHAPE")
    return value


def read_env(path: Path) -> dict[str, str]:
    info = path.stat()
    require(info.st_uid == 0 and stat.S_IMODE(info.st_mode) == 0o600 and not path.is_symlink(),
            "IDP_ENV_PERMISSIONS")
    output = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.lstrip().startswith("#"):
            continue
        key, sep, value = line.partition("=")
        require(bool(sep) and key not in output, "IDP_ENV_FORMAT")
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        output[key] = value
    return output


def api(context: ssl.SSLContext, path: str, token: str | None = None,
        method: str = "GET", document: object | None = None,
        form: dict[str, str] | None = None, allow_not_found: bool = False) -> object:
    headers = {"Accept": "application/json"}
    body = None
    if form is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded"
        body = urlencode(form).encode()
    elif document is not None:
        headers["Content-Type"] = "application/json"
        body = json.dumps(document, separators=(",", ":")).encode()
    if token:
        headers["Authorization"] = "Bearer " + token
    request = Request(BASE + path, data=body, headers=headers, method=method)
    try:
        with urlopen(request, context=context, timeout=8) as response:
            data = response.read(1024 * 1024 + 1)
            require(len(data) <= 1024 * 1024, "IDP_RESPONSE_SIZE")
            if not data:
                return None
            return json.loads(data)
    except HTTPError as error:
        if error.code == 404 and allow_not_found:
            return {"_notFound": True}
        if error.code == 409 and method == "POST":
            raise Refused("IDP_CONCURRENT_CLIENT_CREATE") from None
        raise Refused("IDP_API_REJECTED") from None
    except (URLError, TimeoutError, OSError, json.JSONDecodeError):
        raise Refused("IDP_API_UNAVAILABLE") from None


def get_one(items: object, predicate, code: str) -> dict[str, object]:
    require(type(items) is list, code)
    matches = [item for item in items if type(item) is dict and predicate(item)]
    require(len(matches) == 1, code)
    return matches[0]


def main() -> int:
    try:
        require(os.geteuid() == 0, "ROOT_REQUIRED")
        secrets = read_private_json(SECRET_INPUT)
        required = {"credentialAdminResetClientSecret", "credentialChangeHmacSecret"}
        require(required.issubset(secrets), "SECRET_INPUT_FIELDS")
        client_secret = secrets["credentialAdminResetClientSecret"]
        hmac_secret = secrets["credentialChangeHmacSecret"]
        require(type(client_secret) is str and len(client_secret) >= 32
                and type(hmac_secret) is str and len(hmac_secret) >= 32, "SECRET_INPUT_VALUE")
        ctx = ssl.create_default_context(cafile=str(ROOT / "tls/upstream-ca.crt"))
        ctx.check_hostname = True
        bootstrap = read_env(ROOT / "bootstrap.env")
        admin = api(ctx, "/realms/master/protocol/openid-connect/token", method="POST", form={
            "grant_type": "password", "client_id": "admin-cli",
            "username": bootstrap.get("KC_BOOTSTRAP_ADMIN_USERNAME", ""),
            "password": bootstrap.get("KC_BOOTSTRAP_ADMIN_PASSWORD", ""),
        })
        require(type(admin) is dict and isinstance(admin.get("access_token"), str), "IDP_ADMIN_AUTH")
        token = admin["access_token"]
        clients_path = "/admin/realms/" + REALM + "/clients?clientId=" + CLIENT_ID
        clients = api(ctx, clients_path, token)
        if type(clients) is list and not clients:
            client = {
                "clientId": CLIENT_ID,
                "name": "Siyue Java controlled credential reset",
                "enabled": True,
                "protocol": "openid-connect",
                "publicClient": False,
                "bearerOnly": False,
                "clientAuthenticatorType": "client-secret",
                "secret": client_secret,
                "standardFlowEnabled": False,
                "implicitFlowEnabled": False,
                "directAccessGrantsEnabled": False,
                "serviceAccountsEnabled": True,
                "authorizationServicesEnabled": False,
                "fullScopeAllowed": False,
                "redirectUris": [],
                "webOrigins": [],
            }
            api(ctx, "/admin/realms/" + REALM + "/clients", token, "POST", client)
            clients = api(ctx, clients_path, token)
        client = get_one(clients, lambda item: item.get("clientId") == CLIENT_ID, "IDP_RESET_CLIENT_LOOKUP")
        # Keycloak may apply the Authorization Services default despite the
        # create representation. Disable only that feature before granting
        # service-account roles, then verify the complete client contract.
        immutable_client_contract = {
            "enabled": client.get("enabled") is True,
            "protocol": client.get("protocol") == "openid-connect",
            "publicClient": client.get("publicClient") is False,
            "bearerOnly": client.get("bearerOnly") is False,
            "authenticator": client.get("clientAuthenticatorType") == "client-secret",
            "standardFlow": client.get("standardFlowEnabled") is False,
            "implicitFlow": client.get("implicitFlowEnabled") is False,
            "directGrants": client.get("directAccessGrantsEnabled") is False,
            "serviceAccount": client.get("serviceAccountsEnabled") is True,
            "fullScope": client.get("fullScopeAllowed") is False,
            "redirectUris": client.get("redirectUris") == [],
            "webOrigins": client.get("webOrigins") == [],
        }
        failed_immutable = next((name for name, valid in immutable_client_contract.items() if not valid), None)
        require(failed_immutable is None, "IDP_RESET_CLIENT_CONTRACT_" + str(failed_immutable))
        authorization_services = client.get("authorizationServicesEnabled")
        if authorization_services is True:
            client["authorizationServicesEnabled"] = False
            api(ctx, "/admin/realms/" + REALM + "/clients/" + str(client.get("id")),
                token, "PUT", client)
            clients = api(ctx, clients_path, token)
            client = get_one(clients, lambda item: item.get("clientId") == CLIENT_ID,
                             "IDP_RESET_CLIENT_LOOKUP")
            authorization_services = client.get("authorizationServicesEnabled")
        elif authorization_services is None:
            authz = api(ctx, "/admin/realms/" + REALM + "/clients/" + str(client.get("id"))
                        + "/authz/resource-server", token, allow_not_found=True)
            require(type(authz) is dict and authz.get("_notFound") is True,
                    "IDP_RESET_AUTHORIZATION_SERVICES_UNVERIFIED")
            authorization_services = False
        require(authorization_services is False, "IDP_RESET_AUTHORIZATION_SERVICES_STATE")
        client_contract = {
            "enabled": client.get("enabled") is True,
            "protocol": client.get("protocol") == "openid-connect",
            "publicClient": client.get("publicClient") is False,
            "bearerOnly": client.get("bearerOnly") is False,
            "authenticator": client.get("clientAuthenticatorType") == "client-secret",
            "standardFlow": client.get("standardFlowEnabled") is False,
            "implicitFlow": client.get("implicitFlowEnabled") is False,
            "directGrants": client.get("directAccessGrantsEnabled") is False,
            "serviceAccount": client.get("serviceAccountsEnabled") is True,
            "authorizationServices": authorization_services is False,
            "fullScope": client.get("fullScopeAllowed") is False,
            "redirectUris": client.get("redirectUris") == [],
            "webOrigins": client.get("webOrigins") == [],
        }
        failed_contract = next((name for name, valid in client_contract.items() if not valid), None)
        require(failed_contract is None, "IDP_RESET_CLIENT_CONTRACT_" + str(failed_contract))
        client_uuid = client.get("id")
        require(isinstance(client_uuid, str), "IDP_RESET_CLIENT_ID")
        secret = api(ctx, "/admin/realms/" + REALM + "/clients/" + client_uuid + "/client-secret", token)
        require(type(secret) is dict and isinstance(secret.get("value"), str)
                and hmac.compare_digest(secret["value"], client_secret), "IDP_RESET_SECRET_MISMATCH")
        service_user = api(ctx, "/admin/realms/" + REALM + "/clients/" + client_uuid
                           + "/service-account-user", token)
        require(type(service_user) is dict and isinstance(service_user.get("id"), str),
                "IDP_RESET_SERVICE_ACCOUNT")
        realm_clients = api(ctx, "/admin/realms/" + REALM + "/clients?clientId=realm-management", token)
        realm_management = get_one(realm_clients, lambda item: item.get("clientId") == "realm-management",
                                   "IDP_REALM_MANAGEMENT_CLIENT")
        management_id = realm_management.get("id")
        require(isinstance(management_id, str), "IDP_REALM_MANAGEMENT_CLIENT")
        role = api(ctx, "/admin/realms/" + REALM + "/clients/" + management_id
                   + "/roles/manage-users", token)
        require(type(role) is dict and role.get("name") == "manage-users", "IDP_MANAGE_USERS_ROLE")
        mappings_path = "/admin/realms/" + REALM + "/users/" + service_user["id"]
        mappings_path += "/role-mappings/clients/" + management_id
        mappings = api(ctx, mappings_path, token)
        direct_names = {item.get("name") for item in mappings if type(item) is dict}
        # The reset client reads one explicitly mapped subject before it can
        # update credentials. Keycloak separates that lookup privilege from
        # mutation, so both exact realm-management roles are required.
        allowed_direct = {"manage-users", "view-users"}
        unexpected = direct_names - allowed_direct
        require(not unexpected, "IDP_RESET_CLIENT_EXTRA_ROLES")
        roles_to_add = [role] if "manage-users" not in direct_names else []
        if "view-users" not in direct_names:
            view_role = api(ctx, "/admin/realms/" + REALM + "/clients/" + management_id
                            + "/roles/view-users", token)
            require(type(view_role) is dict and view_role.get("name") == "view-users",
                    "IDP_VIEW_USERS_ROLE")
            roles_to_add.append(view_role)
        if roles_to_add:
            api(ctx, mappings_path, token, "POST", roles_to_add)
        effective = api(ctx, mappings_path + "/composite", token)
        effective_names = {item.get("name") for item in effective if type(item) is dict}
        require("manage-users" in effective_names and "view-users" in effective_names,
                "IDP_RESET_PERMISSION_INSUFFICIENT")
        forbidden = {"realm-admin", "manage-realm", "manage-clients", "view-clients",
                     "manage-authorization", "manage-events"}
        require(not effective_names.intersection(forbidden), "IDP_RESET_PERMISSION_BROAD")
        realm_roles = api(ctx, "/admin/realms/" + REALM + "/users/" + service_user["id"]
                          + "/role-mappings/realm/composite", token)
        realm_role_names = {item.get("name") for item in realm_roles if type(item) is dict}
        require(not realm_role_names.intersection(forbidden), "IDP_RESET_REALM_ROLE_BROAD")
        runtime = read_env(ROOT / "runtime.env")
        event = read_env(ROOT / "java-credential-change.env")
        hmac_runtime = runtime.get("KC_SPI_EVENTS_LISTENER__SIYUE_CREDENTIAL_CHANGE__HMAC_SECRET", "")
        hmac_java = event.get("SIYUE_CREDENTIAL_CHANGE_HMAC_SECRET", "")
        require(hmac.compare_digest(hmac_secret, hmac_runtime) and hmac.compare_digest(hmac_secret, hmac_java),
                "IDP_HMAC_MISMATCH")
        require(runtime.get("KC_SPI_EVENTS_LISTENER__SIYUE_CREDENTIAL_CHANGE__ISSUER") == ISSUER
                and event.get("SIYUE_CREDENTIAL_CHANGE_ISSUER") == ISSUER
                and event.get("SIYUE_CREDENTIAL_CHANGE_ENABLED") == "true", "IDP_EVENT_CONFIGURATION")
        result = {
            "schema": "siyue.formal-keycloak-reset-client-review/v1",
            "realm": REALM,
            "clientId": CLIENT_ID,
            "clientCredentialMatchedPrivateInput": True,
            "clientCredentialsOnly": True,
            "serviceAccountEnabled": True,
            "directRealmManagementRoles": sorted(direct_names | {"manage-users", "view-users"}),
            "effectiveUserManagementRoleVerified": True,
            "realmAdminOrClientManagementGranted": False,
            "credentialChangeHmacMatchesJavaAndProviderConfig": True,
            "publicRouteChanged": False,
        }
        REVIEW.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        parent = REVIEW.parent.stat()
        require(parent.st_uid == 0 and stat.S_IMODE(parent.st_mode) == 0o700, "REVIEW_DIRECTORY_PERMISSIONS")
        require(not REVIEW.exists() and not REVIEW.is_symlink(), "REVIEW_EXISTS")
        fd = os.open(REVIEW, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(result, stream, sort_keys=True, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        digest = hashlib.sha256(REVIEW.read_bytes()).hexdigest()
        print(json.dumps({"result": "verified", "reviewPath": str(REVIEW),
                          "reviewSha256": digest,
                          "directRoleCount": len(direct_names | {"manage-users", "view-users"}),
                          "effectiveUserManagementPermission": True}, sort_keys=True))
        return 0
    except Refused as error:
        print(json.dumps({"result": "blocked", "code": str(error)}, sort_keys=True), file=sys.stderr)
        return 2
    except Exception as error:
        print(json.dumps({"result": "blocked", "code": "UNEXPECTED_" + type(error).__name__},
                         sort_keys=True), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
