#!/usr/bin/env python3
"""Idempotently add a least-privilege Saga DB to the fixed isolated fixture only."""
import json
import subprocess
PROJECT = "robomart-java25-e2e-resume"
ids = subprocess.check_output(["docker", "ps", "-q", "--filter", f"label=com.docker.compose.project={PROJECT}",
    "--filter", "label=com.docker.compose.service=postgres"], text=True).split()
if len(ids) != 1:
    raise RuntimeError("Expected one isolated PostgreSQL container")
labels = json.loads(subprocess.check_output(["docker", "inspect", ids[0]], text=True))[0]["Config"]["Labels"]
if labels.get("com.docker.compose.project") != PROJECT or labels.get("com.docker.compose.service") != "postgres":
    raise RuntimeError("Fixture ownership check failed")
sql = r"""SELECT 'CREATE ROLE saga LOGIN PASSWORD ''robomart_e2e_saga_only'' NOSUPERUSER NOCREATEDB NOCREATEROLE' WHERE NOT EXISTS(SELECT FROM pg_roles WHERE rolname='saga')\gexec
SELECT 'CREATE DATABASE saga_db OWNER saga' WHERE NOT EXISTS(SELECT FROM pg_database WHERE datname='saga_db')\gexec
REVOKE CONNECT ON DATABASE product_db,order_db,inventory_db,payment_db,notification_db,keycloak FROM saga;
"""
# PostgreSQL CONNECT is also granted to PUBLIC; revoke it only in this isolated fixture and restore the existing app role.
for database in ("product_db","order_db","inventory_db","payment_db","notification_db","keycloak"):
    sql += f'REVOKE CONNECT ON DATABASE {database} FROM PUBLIC; GRANT CONNECT ON DATABASE {database} TO robomart;\n'
subprocess.run(["docker", "exec", "-i", ids[0], "psql", "-v", "ON_ERROR_STOP=1", "-U", "robomart", "-d", "postgres"], input=sql, text=True, check=True, stdout=subprocess.DEVNULL)
print("Isolated Saga database/role ready; retained data preserved")
