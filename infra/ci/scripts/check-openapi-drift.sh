#!/usr/bin/env bash
# Check OpenAPI spec drift for each service.
# Compares generated spec in target/ with committed baseline in docs/api/.
# Exits non-zero if drift detected.

set -euo pipefail

SERVICES=("product-service" "cart-service" "order-service" "inventory-service" "payment-service")
DRIFT_FOUND=0

for SERVICE in "${SERVICES[@]}"; do
    GENERATED="backend/${SERVICE}/target/openapi.json"
    BASELINE="docs/api/${SERVICE}-openapi.json"

    if [ ! -f "${GENERATED}" ]; then
        echo "WARNING: Generated spec not found for ${SERVICE} — skipping drift check"
        continue
    fi

    if [ ! -f "${BASELINE}" ]; then
        echo "INFO: No baseline for ${SERVICE} — creating initial baseline"
        mkdir -p "docs/api"
        cp "${GENERATED}" "${BASELINE}"
        continue
    fi

    # Normalize with jq --sort-keys for canonical JSON comparison
    if ! command -v jq &> /dev/null; then
        echo "ERROR: jq is required but not installed" >&2
        exit 1
    fi

    GENERATED_NORMALIZED=$(jq --sort-keys . "${GENERATED}")
    BASELINE_NORMALIZED=$(jq --sort-keys . "${BASELINE}")

    if ! diff <(echo "${GENERATED_NORMALIZED}") <(echo "${BASELINE_NORMALIZED}") > /dev/null 2>&1; then
        echo "ERROR: OpenAPI drift detected for ${SERVICE}!"
        echo "  Generated: ${GENERATED}"
        echo "  Baseline:  ${BASELINE}"
        echo "  Diff:"
        diff <(echo "${GENERATED_NORMALIZED}") <(echo "${BASELINE_NORMALIZED}") || true
        DRIFT_FOUND=1
    else
        echo "OK: ${SERVICE} OpenAPI spec matches baseline"
    fi
done

exit ${DRIFT_FOUND}
