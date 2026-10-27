#!/usr/bin/env python3
"""Build an isolated realm fixture without changing committed developer credentials."""
import json
from pathlib import Path

root = Path(__file__).resolve().parents[2]
realm = json.loads((root / "infra/docker/keycloak/robomart-realm.json").read_text())
# These are public isolated test fixtures, never production credentials.
secrets = {"inventory-service": "e2e-inventory-secret", "notification-service": "e2e-notification-secret"}
for client in realm["clients"]:
    if client["clientId"] in secrets:
        client["secret"] = secrets[client["clientId"]]
output = root / "infra/e2e/.runtime/robomart-realm.json"
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(realm, indent=2) + "\n")
