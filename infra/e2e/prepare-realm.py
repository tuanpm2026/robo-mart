#!/usr/bin/env python3
"""Build an isolated realm fixture without changing the committed developer realm."""
import json
from pathlib import Path

root = Path(__file__).resolve().parents[2]
realm = json.loads((root / "infra/docker/keycloak/robomart-realm.json").read_text())
realm["roles"]["realm"].append({
    "name": "NOTIFICATION_SERVICE",
    "description": "Read order details for notification delivery only",
})
realm["clients"].append({
    "clientId": "notification-service",
    "enabled": True,
    "protocol": "openid-connect",
    "publicClient": False,
    "clientAuthenticatorType": "client-secret",
    "secret": "e2e-notification-secret",
    "standardFlowEnabled": False,
    "directAccessGrantsEnabled": False,
    "serviceAccountsEnabled": True,
    "fullScopeAllowed": True,
    # Explicit mapper avoids relying on realm-import default client scopes.
    "defaultClientScopes": ["profile", "email"],
    "protocolMappers": [{
        "name": "notification-realm-roles",
        "protocol": "openid-connect",
        "protocolMapper": "oidc-usermodel-realm-role-mapper",
        "consentRequired": False,
        "config": {
            "multivalued": "true",
            "access.token.claim": "true",
            "id.token.claim": "false",
            "userinfo.token.claim": "false",
            "claim.name": "realm_access.roles",
            "jsonType.label": "String",
        },
    }],
})
realm["users"].append({
    "username": "service-account-notification-service",
    "enabled": True,
    "serviceAccountClientId": "notification-service",
    "realmRoles": ["NOTIFICATION_SERVICE"],
})
# Test credentials are isolated fixtures; production imports use environment substitution.
for client in realm["clients"]:
    if client["clientId"] == "inventory-service":
        client["secret"] = "e2e-inventory-secret"
output = root / "infra/e2e/.runtime/robomart-realm.json"
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(realm, indent=2) + "\n")
