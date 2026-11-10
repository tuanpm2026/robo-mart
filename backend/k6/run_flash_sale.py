#!/usr/bin/env python3
"""Run a disposable flash sale against the label-verified isolated E2E stack."""
import argparse
from collections import Counter
from datetime import datetime, timezone
from decimal import Decimal
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
PROJECT = "robomart-java25-e2e-resume"
API = "http://127.0.0.1:18080"
KEYCLOAK = "http://localhost:18180"
PASSWORD = "FlashSaleFixture123!"  # Disposable isolated identities only.


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def command(*args):
    result = subprocess.run(args, text=True, capture_output=True, timeout=45)
    if result.returncode:
        # Arguments and process output can contain credentials; do not echo them.
        raise RuntimeError(f"{Path(args[0]).name} operation failed (exit {result.returncode})")
    return result.stdout.strip()


def owned_container(service, port=None):
    ids = command("docker", "ps", "-q", "--filter",
                  f"label=com.docker.compose.project={PROJECT}", "--filter",
                  f"label=com.docker.compose.service={service}").split()
    require(len(ids) == 1, f"Start the isolated stack; expected one running {service}")
    container = json.loads(command("docker", "inspect", ids[0]))[0]
    labels = container["Config"]["Labels"]
    require(labels.get("com.docker.compose.project") == PROJECT
            and labels.get("com.docker.compose.service") == service,
            "Container ownership mismatch")
    if port:
        bindings = container["NetworkSettings"]["Ports"].get(port[0], [])
        require(bool(bindings) and all(p["HostIp"] == "127.0.0.1"
                                      and p["HostPort"] == port[1] for p in bindings),
                f"Unexpected {service} loopback binding")
    return container


def http(base, path, method="GET", data=None, token=None, form=False):
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    if data is not None:
        headers["Content-Type"] = ("application/x-www-form-urlencoded" if form
                                   else "application/json")
        data = (urllib.parse.urlencode(data) if form else json.dumps(data)).encode()
    request = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            body = response.read()
            return response.status, json.loads(body) if body else None, response.headers
    except urllib.error.HTTPError as error:
        return error.code, None, error.headers


def login(username, password, realm="robomart", client="robo-mart-frontend"):
    status, body, _ = http(KEYCLOAK, f"/realms/{realm}/protocol/openid-connect/token",
                           "POST", {"grant_type": "password", "client_id": client,
                                    "username": username, "password": password}, form=True)
    require(status == 200 and body.get("access_token"), f"Fixture login failed: HTTP {status}")
    return body["access_token"]


def query(postgres, database, sql):
    require(database in {"order_db", "inventory_db", "payment_db", "saga_db"}
            and sql.startswith("SELECT ") and ";" not in sql, "Invalid read-only probe")
    return json.loads(command("docker", "exec", "-e",
                             "PGOPTIONS=-c default_transaction_read_only=on", postgres,
                             "psql", "-X", "-v", "ON_ERROR_STOP=1", "-A", "-t",
                             "-U", "robomart", "-d", database, "-c", sql))


def rows(postgres, database, select):
    return query(postgres, database,
                 f"SELECT COALESCE(jsonb_agg(row_to_json(t)), '[]'::jsonb) FROM ({select}) t")


def capture(postgres, product_id):
    require(isinstance(product_id, int) and product_id > 0, "Invalid fixture product")
    orders = rows(postgres, "order_db",
                  "SELECT o.id, o.user_id, o.status, o.cancellation_reason, "
                  "o.orchestrator_owner, o.total_amount FROM orders o WHERE EXISTS "
                  f"(SELECT 1 FROM order_items i WHERE i.order_id=o.id AND i.product_id={product_id}) "
                  "ORDER BY o.id")
    ids = ",".join("'" + str(int(o["id"])) + "'" for o in orders) or "'no-orders'"
    return {
        "orders": orders,
        "workflows": rows(postgres, "saga_db", "SELECT order_id,state,reason,manual_review_at "
                          f"FROM saga_instances WHERE order_id IN ({ids}) ORDER BY order_id"),
        "inventory": rows(postgres, "inventory_db", "SELECT available_quantity, reserved_quantity, "
                          f"total_quantity FROM inventory_items WHERE product_id={product_id}"),
        "reservations": rows(postgres, "inventory_db", "SELECT order_id, status FROM reservations "
                             f"WHERE order_id IN ({ids}) ORDER BY order_id"),
        "movements": rows(postgres, "inventory_db", "SELECT m.order_id, m.type, m.quantity "
                          "FROM stock_movements m JOIN inventory_items i ON i.id=m.inventory_item_id "
                          f"WHERE i.product_id={product_id} AND m.type IN ('RESERVE','COMMIT','RELEASE') "
                          "ORDER BY m.id"),
        "payments": rows(postgres, "payment_db", "SELECT order_id, status, amount, refunded_amount "
                         f"FROM payments WHERE order_id IN ({ids}) ORDER BY order_id"),
        "providerOperations": rows(postgres, "payment_db", "SELECT operation_key, request_order_id, "
                                   "status, amount FROM mock_gateway_operations WHERE "
                                   f"request_order_id IN ({ids}) OR operation_key IN "
                                   f"(SELECT 'refund:' || transaction_id FROM payments "
                                   f"WHERE order_id IN ({ids})) OR (operation_key LIKE 'refund:%' "
                                   f"AND request_identity IN (SELECT transaction_id FROM payments "
                                   f"WHERE order_id IN ({ids}))) ORDER BY operation_key"),
    }


def validate(evidence, user_ids, cleaned=False):
    """Fail closed on missing evidence, extra orders/charges, or incomplete compensation."""
    orders = evidence["orders"]
    require(len(orders) == len(user_ids), "Expected exactly one order per fixture customer")
    require(Counter(o["user_id"] for o in orders) == Counter(user_ids),
            "Checkout replay created extra orders or omitted a customer")
    require(all(o["orchestrator_owner"] == "EXTERNAL" for o in orders),
            "This experiment requires external Saga admission")
    require(all(Decimal(str(o["total_amount"])) == Decimal("9.99") for o in orders),
            "Unexpected server-side order pricing")
    winners = [o for o in orders if o["status"] == "CONFIRMED"]
    if cleaned:
        require(all(o["status"] == "CANCELLED" for o in orders), "Cleanup left non-cancelled orders")
    else:
        require(len(winners) == 1, "Expected exactly one confirmed order")
        require(all(o["status"] in ("CONFIRMED", "CANCELLED") for o in orders),
                "Unresolved orders cannot count as stock rejections")
    require(evidence["inventory"] == [{"available_quantity": int(cleaned),
                                      "reserved_quantity": 0, "total_quantity": int(cleaned)}],
            "Inventory is not fully committed/restored, or stock was oversold")
    payments = evidence["payments"]
    require(len(payments) == 1, "Expected exactly one application payment")
    winner_id = str(payments[0]["order_id"])
    require(winner_id in {str(o["id"]) for o in orders}, "Payment is not tied to this sale")
    if not cleaned:
        require(winner_id == str(winners[0]["id"]), "Payment does not belong to the winner")
    workflows = {str(w["order_id"]): w for w in evidence["workflows"]}
    require(len(evidence["workflows"]) == len(orders) == len(workflows), "Missing/duplicate Saga workflows")
    for order in orders:
        workflow = workflows.get(str(order["id"]))
        expected_state = "CANCELLED" if cleaned or str(order["id"]) != winner_id else "CHECKOUT_CONFIRMED"
        require(workflow is not None and workflow["state"] == expected_state
                and workflow["manual_review_at"] is None, "Saga is unfinished or requires manual review")
        if str(order["id"]) != winner_id:
            require(workflow["reason"] == "Inventory rejected reservation",
                    "A non-stock Saga failure cannot count as a stock rejection")
    require(payments[0]["status"] == ("REFUNDED" if cleaned else "COMPLETED")
            and Decimal(str(payments[0]["amount"])) == Decimal("9.99")
            and Decimal(str(payments[0]["refunded_amount"])) == (Decimal("9.99") if cleaned else 0),
            "Payment amount/status/refund does not match the sale")
    charges = [p for p in evidence["providerOperations"] if p["operation_key"].startswith("charge:")]
    require(len(charges) == 1 and charges[0]["request_order_id"] == winner_id
            and charges[0]["status"] == "COMPLETED"
            and Decimal(str(charges[0]["amount"])) == Decimal("9.99"),
            "Expected exactly one real completed mock-provider charge for the winner")
    refunds = [p for p in evidence["providerOperations"] if p["operation_key"].startswith("refund:")]
    require(len(evidence["providerOperations"]) == 1 + int(cleaned)
            and len(refunds) == int(cleaned), "Unexpected provider operation/refund count")
    if cleaned:
        require(refunds[0]["status"] == "REFUNDED"
                and Decimal(str(refunds[0]["amount"])) == Decimal("9.99"),
                "Provider did not complete the full cleanup refund")
    require(Counter((str(r["order_id"]), r["status"]) for r in evidence["reservations"])
            == Counter((str(o["id"]), "RELEASED" if cleaned or str(o["id"]) != winner_id
                        else "COMMITTED") for o in orders), "Reservation evidence is incomplete")
    expected = [(winner_id, "RESERVE", 1), (winner_id, "COMMIT", 1)]
    if cleaned:
        expected.append((winner_id, "RELEASE", 1))
    require(Counter((str(m["order_id"]), m["type"], m["quantity"]) for m in evidence["movements"])
            == Counter(expected), "Missing/duplicate stock movements or stock granted to a loser")


def await_valid(postgres, product_id, user_ids, timeout, cleaned=False):
    deadline = time.monotonic() + timeout
    while True:
        evidence = capture(postgres, product_id)
        try:
            validate(evidence, user_ids, cleaned)
            return evidence
        except AssertionError:
            if time.monotonic() >= deadline:
                raise
            time.sleep(2)


class Experiment:
    def __init__(self, args):
        self.args = args
        self.run_id = "flash-" + uuid.uuid4().hex
        self.users = []
        self.product_id = None
        self.realm_token = None
        self.admin_token = None
        self.payment_stopped = False
        self.process = None
        self.report = {"runId": self.run_id, "startedAt": datetime.now(timezone.utc).isoformat(),
                       "customers": args.customers, "paymentOutage": args.payment_outage,
                       "scope": "Isolated local fixture; mock payment provider; correctness experiment"}

    def preflight(self):
        require(shutil.which("k6"), "Install k6 before running this experiment")
        self.report["sourceCommit"] = command("git", "-C", str(ROOT), "rev-parse", "HEAD")
        self.report["k6Version"] = command("k6", "version")
        keycloak = owned_container("keycloak", ("8180/tcp", "18180"))
        owned_container("api-gateway", ("8080/tcp", "18080"))
        self.postgres = owned_container("postgres")["Id"]
        self.payment = owned_container("payment-service")["Id"]
        owned_container("checkout-saga-service")
        order = owned_container("order-service")
        env = dict(e.split("=", 1) for e in order["Config"]["Env"] if "=" in e)
        require(env.get("ROBOMART_ORDER_EXTERNAL_SAGA_ENABLED") == "true",
                "Enable external Saga admission in the isolated fixture")
        self.report["images"] = {s: owned_container(s)["Image"] for s in
                                 ("order-service", "inventory-service", "payment-service", "checkout-saga-service")}
        env = dict(e.split("=", 1) for e in keycloak["Config"]["Env"] if "=" in e)
        self.realm_token = login(env["KC_BOOTSTRAP_ADMIN_USERNAME"],
                                 env["KC_BOOTSTRAP_ADMIN_PASSWORD"], "master", "admin-cli")
        self.admin_token = login("demo-admin", "admin123")

    def provision(self):
        status, role, _ = http(KEYCLOAK, "/admin/realms/robomart/roles/CUSTOMER", token=self.realm_token)
        require(status == 200, "Missing CUSTOMER realm role")
        for index in range(self.args.customers):
            name = f"{self.run_id}-{index}"
            status, _, headers = http(KEYCLOAK, "/admin/realms/robomart/users", "POST", {
                "username": name, "firstName": "Flash", "lastName": "Fixture",
                "email": name + "@example.test", "enabled": True, "emailVerified": True,
                "credentials": [{"type": "password", "value": PASSWORD, "temporary": False}],
            }, self.realm_token)
            require(status == 201, f"Create disposable customer failed: HTTP {status}")
            user = {"id": headers["Location"].rsplit("/", 1)[-1], "username": name}
            self.users.append(user)  # Register cleanup before any subsequent operation can fail.
            self.report["customerIds"] = [u["id"] for u in self.users]
            status, _, _ = http(KEYCLOAK, f'/admin/realms/robomart/users/{user["id"]}/role-mappings/realm',
                                 "POST", [role], self.realm_token)
            require(status == 204, f"Customer role assignment failed: HTTP {status}")
        status, body, _ = http(API, "/api/v1/admin/products", "POST", {
            "name": self.run_id, "sku": self.run_id, "categoryId": 1,
            "price": "9.99", "brand": "Flash sale fixture",
        }, self.admin_token)
        require(status == 201, f"Create disposable product failed: HTTP {status}")
        self.product_id = int(body["data"]["id"])
        self.report["productId"] = self.product_id
        deadline = time.monotonic() + 60
        while True:
            status, _, _ = http(API, f"/api/v1/admin/inventory/{self.product_id}/restock", "PUT",
                                 {"quantity": 1, "reason": self.run_id}, self.admin_token)
            if status == 200:
                break
            require(status == 404 and time.monotonic() < deadline,
                    f"Inventory bootstrap/restock failed: HTTP {status}")
            time.sleep(1)
        self.report["initialInventory"] = capture(self.postgres, self.product_id)["inventory"]
        require(self.report["initialInventory"] == [{"available_quantity": 1,
                "reserved_quantity": 0, "total_quantity": 1}], "Initial stock is not exactly one")

    def run(self, directory):
        customers = [{"token": login(u["username"], PASSWORD)} for u in self.users]
        fixture = directory / "fixture.json"
        fixture.write_text(json.dumps({"runId": self.run_id, "productId": self.product_id,
                                      "customers": customers, "timeoutSeconds": self.args.timeout}))
        fixture.chmod(0o600)
        env = {k: v for k, v in os.environ.items() if not k.startswith("K6_")}
        env.update(FLASH_SALE_FIXTURE=str(fixture), FLASH_SALE_SUMMARY=str(directory / "summary.json"))
        if self.args.payment_outage:
            # Check ownership immediately before mutation and restore in cleanup, even on failure.
            require(owned_container("payment-service")["Id"] == self.payment, "Payment container changed")
            self.payment_stopped = True
            command("docker", "stop", self.payment)
        with (directory / "k6.log").open("w") as log:
            self.process = subprocess.Popen(["k6", "run", "--quiet", "--log-format", "raw",
                                            str(ROOT / "backend/k6/scripts/flash-sale.js")],
                                           env=env, stdout=log, stderr=subprocess.STDOUT)
            if self.args.payment_outage:
                deadline = time.monotonic() + 90
                while True:
                    held = capture(self.postgres, self.product_id)
                    if (len(held["orders"]) == len(self.users) and held["inventory"] == [
                            {"available_quantity": 0, "reserved_quantity": 1, "total_quantity": 1}]):
                        break
                    require(self.process.poll() is None and time.monotonic() < deadline,
                            "Did not observe all original orders with stock held during Payment outage")
                    time.sleep(2)
                require(not held["payments"] and not held["providerOperations"]
                        and all(o["status"] in ("PENDING", "CANCELLATION_PENDING") for o in held["orders"]),
                        "Unexpected settlement while Payment is stopped")
                self.report["duringOutage"] = held
                command("docker", "start", self.payment)
                self.payment_stopped = False
            code = self.process.wait(timeout=self.args.timeout + 90)
        self.report["k6ExitCode"] = code
        self.report["k6Summary"] = json.loads((directory / "summary.json").read_text())
        self.report["afterLoad"] = capture(self.postgres, self.product_id)
        # The raw output contains only statuses/IDs, never HTTP bodies or tokens.
        print((directory / "k6.log").read_text().strip(), flush=True)
        require(code == 0, f"k6 thresholds failed (exit {code})")
        self.report["beforeCleanup"] = await_valid(self.postgres, self.product_id,
                                                    [u["id"] for u in self.users], 60)
        if self.args.payment_outage:
            require({o["id"] for o in self.report["duringOutage"]["orders"]}
                    == {o["id"] for o in self.report["beforeCleanup"]["orders"]},
                    "Recovery must finish the same original orders")

    def cleanup(self):
        errors = []
        if self.payment_stopped:
            try:
                command("docker", "start", self.payment)
                self.payment_stopped = False
            except Exception as error:
                errors.append(str(error))
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=10)
        if self.product_id:
            try:
                # Refresh credentials before cleanup; the experiment may outlive a JWT.
                tokens = {u["id"]: login(u["username"], PASSWORD) for u in self.users}
                current = capture(self.postgres, self.product_id)
                for order in current["orders"]:
                    if order["status"] != "CANCELLED":
                        status, _, _ = http(API, f'/api/v1/orders/{order["id"]}/cancel', "POST",
                                             {"reason": "Flash sale fixture cleanup"}, tokens[order["user_id"]])
                        require(status in (200, 202), f"Cleanup cancellation failed: HTTP {status}")
                if "beforeCleanup" in self.report:
                    self.report["afterCleanup"] = await_valid(self.postgres, self.product_id,
                                                               [u["id"] for u in self.users], 90, True)
                else:
                    deadline = time.monotonic() + 90
                    while any(o["status"] != "CANCELLED" for o in capture(self.postgres, self.product_id)["orders"]):
                        require(time.monotonic() < deadline, "Failed-run cleanup left unresolved orders")
                        time.sleep(2)
                self.admin_token = login("demo-admin", "admin123")
                status, _, _ = http(API, f"/api/v1/admin/products/{self.product_id}", "DELETE", token=self.admin_token)
                require(status == 204, f"Product cleanup failed: HTTP {status}")
            except Exception as error:
                errors.append(str(error))
        # Retain identities if compensation is unresolved, so the user can inspect/resume it.
        if not errors and self.realm_token:
            try:
                env = dict(e.split("=", 1) for e in owned_container("keycloak")["Config"]["Env"] if "=" in e)
                self.realm_token = login(env["KC_BOOTSTRAP_ADMIN_USERNAME"], env["KC_BOOTSTRAP_ADMIN_PASSWORD"],
                                         "master", "admin-cli")
                for user in self.users:
                    status, _, _ = http(KEYCLOAK, f'/admin/realms/robomart/users/{user["id"]}',
                                         "DELETE", token=self.realm_token)
                    require(status == 204, f"Customer cleanup failed: HTTP {status}")
            except Exception as error:
                errors.append(str(error))
        self.report["cleanup"] = {"status": "failed" if errors else "passed", "errors": errors}
        return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--customers", type=int, default=100)
    parser.add_argument("--timeout", type=int, default=180, help="Checkout deadline in seconds")
    parser.add_argument("--payment-outage", action="store_true")
    parser.add_argument("--output", type=Path, help="Evidence JSON (never contains tokens)")
    args = parser.parse_args()
    parser.error("customers must be 2..100; timeout must be 60..600") if not (
        2 <= args.customers <= 100 and 60 <= args.timeout <= 600) else None
    experiment = Experiment(args)
    output = args.output or ROOT / "backend/k6/results" / (experiment.run_id + ".json")
    require(not output.exists(), "Refusing to overwrite existing experiment evidence")
    # TemporaryDirectory is mode 0700; secret fixture bytes disappear on every normal exit.
    with tempfile.TemporaryDirectory(prefix="robomart-flash-") as temporary:
        try:
            experiment.preflight()
            print(f"Provisioning {args.customers} disposable customers and one stock unit...", flush=True)
            experiment.provision()
            experiment.run(Path(temporary))
            experiment.report["status"] = "passed"
        except (Exception, KeyboardInterrupt) as error:
            experiment.report["status"] = "failed"
            experiment.report["error"] = str(error) if not isinstance(error, KeyboardInterrupt) else "Interrupted"
        finally:
            if experiment.cleanup():
                experiment.report["status"] = "failed"
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(json.dumps(experiment.report, indent=2) + "\n")
    print(f'{experiment.report["status"].upper()}: {output}', flush=True)
    return 0 if experiment.report["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
