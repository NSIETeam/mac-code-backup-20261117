#!/usr/bin/env python3
"""Generate a no-secret Keycloak realm/client import package for review only."""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from urllib.parse import urlsplit

REALM = "siyue-production"
CLIENT = "siyue-crm-browser"
ORIGIN = "https://www.siyuejn.com"
REDIRECT = f"{ORIGIN}/crm/"
ISSUER = f"{ORIGIN}/crm-auth/realms/{REALM}"
AUDIENCE = "siyue-api"
THEME = "siyue"


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
    client: dict[str, object] = {
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
        "redirectUris": [REDIRECT],
        "webOrigins": [ORIGIN],
        "attributes": {
            "pkce.code.challenge.method": "S256",
            "post.logout.redirect.uris": REDIRECT,
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
    }
    return {
        "realm": REALM,
        "enabled": True,
        "displayName": "思跃 CRM",
        "loginTheme": THEME,
        "internationalizationEnabled": True,
        "defaultLocale": "zh-Hans",
        "supportedLocales": ["zh-Hans"],
        "registrationAllowed": False,
        "resetPasswordAllowed": False,
        "rememberMe": True,
        "bruteForceProtected": True,
        "clients": [client],
        "roles": {"realm": [], "client": {}},
        "users": [],
    }


def validate(realm: dict[str, object]) -> None:
    clients = realm.get("clients")
    if not isinstance(clients, list) or len(clients) != 1 or not isinstance(clients[0], dict):
        raise ValueError("realm must contain exactly the reviewed browser client")
    client = clients[0]
    if realm.get("realm") != REALM or client.get("clientId") != CLIENT:
        raise ValueError("production realm/client names are fixed")
    if realm.get("users") != []:
        raise ValueError("account imports must use the separate trusted identity migration")
    if client.get("redirectUris") != [REDIRECT] or client.get("webOrigins") != [ORIGIN]:
        raise ValueError("wildcard or non-canonical browser origins are forbidden")
    if client.get("publicClient") is not True or any(client.get(key) is not False for key in (
        "implicitFlowEnabled", "directAccessGrantsEnabled", "serviceAccountsEnabled",
        "authorizationServicesEnabled",
    )):
        raise ValueError("client must be public OIDC auth-code only with PKCE")
    attributes = client.get("attributes")
    if not isinstance(attributes, dict) or attributes.get("pkce.code.challenge.method") != "S256":
        raise ValueError("PKCE S256 is mandatory")
    if realm.get("loginTheme") != THEME or realm.get("defaultLocale") != "zh-Hans":
        raise ValueError("reviewed Chinese login theme/locale must be retained")
    issuer = urlsplit(ISSUER)
    if issuer.scheme != "https" or issuer.hostname != "www.siyuejn.com" or issuer.path != f"/crm-auth/realms/{REALM}":
        raise ValueError("issuer must use the reviewed canonical HTTPS path")
    mappers = client.get("protocolMappers", [])
    generation = [item for item in mappers if isinstance(item, dict)
                  and item.get("config", {}).get("claim.name") == "siyue_credential_generation"]
    if generation != [credential_generation_mapper()]:
        raise ValueError("credential generation mapper must match the reviewed integer claim")
    serialized = json.dumps(realm, sort_keys=True).lower()
    if any(marker in serialized for marker in ("candidate", "migration-v101")):
        raise ValueError("candidate identifiers and secret-bearing fields are forbidden")

    def reject_secret_fields(value: object) -> None:
        if isinstance(value, dict):
            if any(key.lower() in {"secret", "clientsecret", "password", "credentialdata"} for key in value):
                raise ValueError("credentials must not be embedded in the reviewed import")
            for child in value.values():
                reject_secret_fields(child)
        elif isinstance(value, list):
            for child in value:
                reject_secret_fields(child)

    reject_secret_fields(realm)


def write_private(output: Path, realm: dict[str, object]) -> None:
    if output.exists() or output.is_symlink():
        raise ValueError("output must be a new directory")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.mkdir(mode=0o700)
    os.chmod(output, 0o700)
    payloads = {
        "realm-import.json": realm,
        "review-manifest.json": {
            "kind": "review-only-keycloak-import",
            "realm": REALM,
            "client": CLIENT,
            "issuer": ISSUER,
            "canonicalRedirect": REDIRECT,
            "requiredNginxPath": "/crm-auth/",
            "audience": AUDIENCE,
            "theme": THEME,
            "defaultLocale": "zh-Hans",
            "accountImport": "separate trusted V101 identity-migration flow; not included",
            "executeAutomatically": False,
        },
    }
    for filename, value in payloads.items():
        target = output / filename
        fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(target, 0o600)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", metavar="NEW_PRIVATE_DIR", type=Path,
                        help="write review artifacts to a newly created 0700 directory; never contacts Keycloak")
    args = parser.parse_args(argv)
    realm = build_realm()
    validate(realm)
    if args.write:
        write_private(args.write, realm)
    summary = {
        "mode": "local-review-package" if args.write else "dry-run",
        "realm": REALM,
        "client": CLIENT,
        "issuer": ISSUER,
        "redirectUriCount": 1,
        "userCount": 0,
        "containsCredentials": False,
        "contactsKeycloak": False,
    }
    sys.stdout.write(json.dumps(summary, ensure_ascii=False) + "\n")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except (OSError, ValueError) as exc:
        sys.stderr.write(f"KEYCLOAK_SPEC_REJECTED: {type(exc).__name__}\n")
        raise SystemExit(2)
