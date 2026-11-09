# Markserver demo data and live checkout validation

Date: 2026-10-29 (Asia/Saigon). Environment: markserver, Kubernetes context
`kubernetes-admin@kubernetes`, namespace `robomart`. Public Gateway:
`https://api.tuanpm.xyz`; tests used the same Gateway via its existing NodePort 32210.

## Retained demo data

| Product ID | SKU | Name | Price | Available / reserved / total |
| --- | --- | --- | --- | --- |
| 1 | DEMO-MARK-001 | Demo Explorer Robot | 19.90 | 20 / 0 / 20 |
| 2 | DEMO-MARK-002 | Demo Helper Robot | 49.90 | 20 / 0 / 20 |
| 3 | DEMO-MARK-003 | Demo Builder Robot | 99.90 | 20 / 0 / 20 |

A separate `Robo-Mart Demo` category and one generated robot PNG per product are
retained for interactive use. Products and images were created through authenticated
Product APIs. Missing category and zero-stock inventory rows were initialized with
additive SQL in their respective owning databases; stock was added through the
Inventory admin restock API with an explicit demo reason. Existing rows were not
reset. The repository's destructive repeatable seed scripts were not executed.

Product's stock snapshot defaults to zero, and the current restock implementation
does not publish a stock snapshot event. Bootstrap therefore initialized only these
three demo Product stock snapshots from Inventory, followed by normal Product admin
updates to invalidate detail caches/publish updated snapshots and an admin reindex.
This is fixture initialization, not a fix for ongoing catalog stock synchronization.

## Executed checks

- `POST /api/v1/admin/products/{id}/images`: multipart PNG uploads returned 201.
  Anonymous GET through Gateway `/images/...` returned byte-identical content,
  verified with SHA-256. One image per product remains in MinIO and Product metadata.
- Uploaded a separate disposable image and deleted it through Product API.
  Its public Gateway path subsequently returned 404.
- Authenticated `POST /api/v1/cart/items` ignored forged client name/price and
  returned the catalog name and 19.90 price.
- `POST /api/v1/orders` with a unique `Idempotency-Key`, quantity one, and forged
  unit price 0.01 returned 201, order **1**, status **CONFIRMED**, total **19.90**.
- Repeating the identical order request with the same key returned order 1.
  The owning Payment database contained exactly one COMPLETED payment.
- Awaited the owning Inventory reservation reaching COMMITTED and verified stock
  changed from `20|0|20` to `19|0|19` exactly once.
- The customer identity received 403 from `/api/v1/admin/orders`.
- `POST /api/v1/orders/1/cancel` completed; owning Order/Payment databases confirmed
  **CANCELLED / REFUNDED**. Inventory returned to exactly `20|0|20`.
- Notification database recorded one `ORDER_CONFIRMED|SENT` and one
  `PAYMENT_SUCCESS|SENT` entry for order 1. These are application delivery records,
  not proof of delivery to an external email inbox.
- Public catalog and search returned all three demo products with stock 20.
  All three catalog image URLs returned HTTP 200 and PNG content anonymously.
  Search uses its existing response shape; image URLs were checked from catalog.

Payment uses the deployed `MockPaymentGateway` implementation, backed by its
idempotent ledger; no real payment provider was invoked. The cancelled order,
refunded mock payment, stock movements and audit history remain for inspection.

## Identity cleanup and scope

Temporary confidential service-account clients with separate ADMIN/CUSTOMER roles
and the existing API audience were used; credentials remained in process memory.
The initial cleanup encountered an expired Keycloak admin token (401). A fresh admin
token removed both test clients and verified their absence. A subsequent temporary
admin client used for fixture synchronization was also removed. Existing users and
clients were preserved.

No production application source, deployment configuration or payment records outside
this demo order were changed. This was live API validation; browser-driven UI checkout,
real-provider payments and chaos scenarios were not executed. Operational evidence
is also retained on markserver at
`/home/tuanpm/.robomart-ops/demo-validation-2026-10-29.json` without credentials.
