# k6 Performance Tests

## Prerequisites

- k6 installed: `brew install k6` (Mac) or https://k6.io/docs/getting-started/installation/
- Full stack running: `cd infra/docker && docker-compose --profile core --profile app up -d`
- Test users seeded (see below)

## Test User Setup

Before running, seed 100 test users in Keycloak realm `robomart`:

- Usernames: `testuser001@robomart.com` through `testuser100@robomart.com`
- Password: `testpassword123`
- Role: `ROLE_CUSTOMER`

Also seed a flash-sale product with `stock_quantity = 1`:

```sql
-- Run against the product-service database
INSERT INTO products (name, description, price, stock_quantity)
VALUES ('Flash Sale Item', 'Limited quantity item for testing', 9.99, 1);
-- Note the ID and set FLASH_SALE_PRODUCT_ID env var accordingly
```

## Running Tests

### Concurrent Orders (AC2 — NFR3, NFR6)

Tests 100 concurrent order placements with no data corruption or overselling.
Product must have `stock_quantity >= 100`.

```bash
PRODUCT_ID=1 BASE_URL=http://localhost:8080 k6 run backend/k6/scripts/concurrent-orders.js
```

### Flash Sale (AC3 — NFR6)

Tests that exactly 1 order succeeds when 100 users compete for 1 item simultaneously.
**IMPORTANT**: `FLASH_SALE_PRODUCT_ID` must point to a product with `stock_quantity = 1` exactly.

```bash
FLASH_SALE_PRODUCT_ID=2 BASE_URL=http://localhost:8080 k6 run backend/k6/scripts/flash-sale.js
```

## Thresholds

| Metric | Threshold | NFR |
|--------|-----------|-----|
| `saga_completion_time` p95 | `< 3000ms` | NFR3 |
| `oversell_errors` count | `== 0` | NFR6 |
| `successful_orders` count (flash sale) | `== 1` | NFR6 |
| `out_of_stock_responses` count (flash sale) | `>= 99` | NFR6 |
| `duplicate_charges` count (flash sale) | `== 0` | NFR6 |
| `http_req_failed` rate | `< 0.01` | NFR3 |

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `BASE_URL` | `http://localhost:8080` | API Gateway base URL |
| `KEYCLOAK_URL` | `http://localhost:8180` | Keycloak server URL |
| `PRODUCT_ID` | `1` | Product ID for concurrent-orders test (stock >= 100) |
| `FLASH_SALE_PRODUCT_ID` | `2` | Product ID for flash-sale test (stock = 1 exactly) |
