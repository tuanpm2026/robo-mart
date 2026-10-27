#!/usr/bin/env python3
"""Reconcile audience mappers only in the guarded, persistent E2E Keycloak realm."""
import json
import subprocess
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

PROJECT = "robomart-java25-e2e-resume"
BASE = "http://127.0.0.1:18180"
CLIENTS = {
    "robo-mart-frontend", "robo-mart-admin", "robo-mart-gateway",
    "inventory-service", "notification-service",
}


def owned_keycloak():
    ids = subprocess.check_output([
        "docker", "ps", "-q", "--filter", f"label=com.docker.compose.project={PROJECT}",
        "--filter", "label=com.docker.compose.service=keycloak",
    ], text=True).split()
    if len(ids) != 1:
        raise RuntimeError("Expected exactly one running isolated Keycloak container")
    container = json.loads(subprocess.check_output(["docker", "inspect", ids[0]], text=True))[0]
    labels = container["Config"]["Labels"]
    if (labels.get("com.docker.compose.project") != PROJECT
            or labels.get("com.docker.compose.service") != "keycloak"):
        raise RuntimeError("Isolated Keycloak ownership check failed")
    ports = container["NetworkSettings"]["Ports"].get("8180/tcp", [])
    if not ports or any(p["HostIp"] != "127.0.0.1" or p["HostPort"] != "18180" for p in ports):
        raise RuntimeError("Isolated Keycloak must use its fixed loopback port")
    return dict(value.split("=", 1) for value in container["Config"]["Env"] if "=" in value)


def request(path, method="GET", data=None, token=None, form=False):
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if data is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
        data = urllib.parse.urlencode(data).encode() if form else json.dumps(data).encode()
    call = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(call, timeout=15) as response:
            payload = response.read()
            return json.loads(payload) if payload else None
    except urllib.error.HTTPError as failure:
        # Never print token responses, credentials or full client representations.
        raise RuntimeError(f"Isolated Keycloak request failed with HTTP {failure.code}") from None


def main():
    environment = owned_keycloak()
    realm_file = Path(__file__).resolve().parent / ".runtime/robomart-realm.json"
    fixture = json.loads(realm_file.read_text())
    if fixture["realm"] != "robomart" or {c["clientId"] for c in fixture["clients"]} != CLIENTS:
        raise RuntimeError("Unexpected isolated realm/client fixture")
    token = request("/realms/master/protocol/openid-connect/token", "POST", {
        "grant_type": "password", "client_id": "admin-cli",
        "username": environment["KC_BOOTSTRAP_ADMIN_USERNAME"],
        "password": environment["KC_BOOTSTRAP_ADMIN_PASSWORD"],
    }, form=True)["access_token"]
    updates = 0
    for client in fixture["clients"]:
        desired = next(m for m in client["protocolMappers"] if m["name"] == "robomart-api-audience")
        if (desired["protocolMapper"] != "oidc-audience-mapper"
                or desired["config"]["included.custom.audience"] != "robomart-api"):
            raise RuntimeError("Unexpected API audience mapper")
        matches = request("/admin/realms/robomart/clients?" + urllib.parse.urlencode({
            "clientId": client["clientId"],
        }), token=token)
        if len(matches) != 1:
            raise RuntimeError("Expected one isolated realm client; no clients are created or deleted here")
        path = f'/admin/realms/robomart/clients/{matches[0]["id"]}/protocol-mappers/models'
        current = [m for m in request(path, token=token) if m["name"] == desired["name"]]
        if len(current) > 1:
            raise RuntimeError("Duplicate isolated audience mappers require review")
        if not current:
            request(path, "POST", desired, token)
            updates += 1
        elif current[0]["protocolMapper"] != desired["protocolMapper"]:
            raise RuntimeError("Refusing to replace a different protocol mapper")
        elif any(current[0].get("config", {}).get(k) != v for k, v in desired["config"].items()):
            updated = current[0] | desired
            updated["config"] = current[0].get("config", {}) | desired["config"]
            request(path + "/" + current[0]["id"], "PUT", updated, token)
            updates += 1
    print(f"Isolated API audience mappers reconciled ({updates} updates); existing data preserved")


if __name__ == "__main__":
    main()
