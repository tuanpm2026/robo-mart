#!/usr/bin/env bash
# Dedicated project only; do not pass arbitrary Compose flags/project overrides.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROJECT=robomart-java25-e2e-resume
COMPOSE=(docker compose --env-file /dev/null --project-name "$PROJECT" --file "$ROOT/infra/docker/docker-compose.e2e.yml")
SERVICES=(product-service cart-service inventory-service payment-service order-service notification-service api-gateway)

case "${1:-}" in
  prepare)
    python3 "$ROOT/infra/e2e/prepare-realm.py"
    "${COMPOSE[@]}" config --quiet
    ;;
  build)
    python3 "$ROOT/backend/scripts/render-dockerfiles.py" --check
    "$0" prepare
    for service in "${SERVICES[@]}"; do
      test -s "$ROOT/backend/$service/target/$service-0.0.1-SNAPSHOT.jar" || {
        echo "Missing built JAR for $service; install backend modules first." >&2; exit 1;
      }
    done
    # Serial image builds avoid memory contention on Docker Desktop's 8 GiB VM.
    for service in minio postgres keycloak "${SERVICES[@]}"; do
      "${COMPOSE[@]}" build "$service"
    done
    ;;
  up)
    # Dependencies first, then Spring contexts one at a time.
    "${COMPOSE[@]}" up -d --wait --wait-timeout 600 postgres redis elasticsearch kafka schema-registry keycloak minio
    "${COMPOSE[@]}" up -d minio-init
    "${COMPOSE[@]}" wait minio-init
    python3 "$ROOT/infra/e2e/configure-kafka.py" --enable-consumer-protocol
    # Startup imports do not update an existing realm; preserve data and reconcile only test mappers.
    python3 "$ROOT/infra/e2e/configure-realm.py"
    for service in "${SERVICES[@]}"; do
      "${COMPOSE[@]}" up -d --no-deps --wait --wait-timeout 480 "$service"
    done
    ;;
  ps) "${COMPOSE[@]}" ps ;;
  logs) "${COMPOSE[@]}" logs --no-color --tail 150 ;;
  down)
    # Keep this project's data for diagnosis; never remove existing user volumes.
    "${COMPOSE[@]}" down --remove-orphans
    ;;
  payment-stop|payment-start)
    id="$("${COMPOSE[@]}" ps -a -q payment-service)"
    test -n "$id" || { echo 'No isolated payment container found.' >&2; exit 1; }
    actual_project="$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "$id")"
    actual_service="$(docker inspect --format '{{index .Config.Labels "com.docker.compose.service"}}' "$id")"
    test "$actual_project" = "$PROJECT" && test "$actual_service" = payment-service || {
      echo 'Container ownership check failed.' >&2; exit 1;
    }
    if [ "$1" = payment-stop ]; then docker stop "$id"; else docker start "$id"; fi
    ;;
  test)
    # Tokens stay in process environment; do not echo them or put them in Maven arguments.
    export E2E_BASE_URL=http://127.0.0.1:18080
    export E2E_KEYCLOAK_URL=http://localhost:18180
    export E2E_ORDER_URL=http://127.0.0.1:18083
    export E2E_INVENTORY_URL=http://127.0.0.1:18084
    export E2E_PAYMENT_URL=http://127.0.0.1:18086
    export E2E_COMPOSE_PROJECT="$PROJECT"
    export E2E_PAYMENT_SERVICE=payment-service
    E2E_ADMIN_TOKEN="$(python3 - <<'PY'
import json, urllib.parse, urllib.request
body = urllib.parse.urlencode({"grant_type": "password", "client_id": "robo-mart-frontend", "username": "demo-admin", "password": "admin123"}).encode()
request = urllib.request.Request("http://localhost:18180/realms/robomart/protocol/openid-connect/token", data=body)
with urllib.request.urlopen(request, timeout=15) as response:
    print(json.load(response)["access_token"])
PY
)"
    export E2E_ADMIN_TOKEN
    export CHAOS_ADMIN_TOKEN="$E2E_ADMIN_TOKEN"
    cd "$ROOT/backend"
    ./mvnw verify -pl :e2e-tests -DskipE2ETests=false -De2e.enabled=true -Dchaos.enabled=true
    ;;
  *) echo "Usage: $0 {prepare|build|up|ps|logs|test|down|payment-stop|payment-start}" >&2; exit 2 ;;
esac
