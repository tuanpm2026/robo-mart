# Inventory and catalog stock synchronization

Inventory owns stock. Product stores an eventually consistent available-stock
projection for catalog, detail, search and the storefront's availability controls.
Checkout still validates and reserves stock atomically in Inventory; a catalog
snapshot never authorizes a reservation.

## Event flow

1. Product creation commits its existing `PRODUCT_CREATED` outbox record. Inventory's
   `inventory-product-created` consumer inserts a zero-stock item only if none
   exists. Replaying creation preserves existing quantities, reservations and audit
   history. The Product event's `stockQuantity` is not trusted as Inventory stock.
2. Initialization, restock, reserve, commit and release write a `stock_updated`
   outbox row in the same transaction as the stock change. Hibernate mutations
   flush before the snapshot captures the resulting row version; conditional SQL
   reserve already increments that version atomically.
3. The shared fenced outbox dispatcher publishes `StockUpdatedEvent` on
   `inventory.stock.updated`, keyed by product ID. Existing reservation, release
   and low-stock event contracts remain unchanged.
4. Product's `product-inventory-stock` consumer locks the product row and accepts
   only a strictly newer `inventoryVersion`. It saves the absolute available
   quantity and Inventory version, advances Product's independent optimistic-lock
   version, and creates the normal `PRODUCT_UPDATED` outbox row in one transaction.
   A duplicate or delayed snapshot changes neither stock nor the Product outbox.
5. Existing Product events drive Elasticsearch, Redis invalidation and Cart's
   product updates. Index consumers wait for search visibility before evicting
   caches again. A conflict with an already indexed bootstrap/reindex version
   explicitly refreshes that winning document before cache invalidation.

Delivery is at least once. Absolute snapshots and a persisted source-version fence
provide idempotency without increment/decrement replay errors. Failed database or
outbox writes roll back together. Invalid or missing-product snapshots use the
existing bounded Kafka retry and `inventory.stock.updated.DLT` handling; they are
not silently acknowledged as successful projections.

## Schema and existing data

- Inventory migration `V9__publish_initial_stock_snapshots.sql` enqueues one snapshot
  per existing inventory row without changing stock, reservations or movements.
- Product migration `V9__add_inventory_snapshot_version.sql` adds
  `products.inventory_version`, initially `-1`. Inventory's first nonnegative
  version can therefore replace a legacy or manually initialized Product snapshot.
- `StockUpdatedEvent` is a new Avro record; existing schemas are unchanged. Its
  `version` is the event contract version, while the separate `inventoryVersion`
  is the monotonic Inventory row version. It carries an absolute available quantity.
- New products receive zero stock asynchronously. An immediate restock can return
  404 until their creation event is consumed. Refresh the inventory list after
  creation; do not initialize or copy business data manually to work around lag.

## Rollout and diagnosis

Deploy the new Product consumer/migration and Inventory producer/migration together,
preferably Product first. Provision the three-partition stock topic and its DLT
where topic creation is managed externally. Application KafkaAdmin configuration
creates them in the owned single-broker environments. Preserve existing topic
offsets, databases and volumes.

Allow the initial snapshot backlog to drain before judging catalog availability.
Check Inventory row quantity/version -> Inventory outbox -> stock topic/consumer
lag -> Product quantity/inventory version -> Product outbox -> index/cache consumer.
Product's optimistic-lock version is independent of Inventory's version and may
also advance during normal catalog edits. Do not reset either version or replay
stock deltas through SQL. Repair a failed consumer/DLT cause before replaying it.

## Verification

`InventoryStockSnapshotIT` covers mutation snapshots, operation replay, zero-stock
initialization and transaction rollback, including a release that must remain
recoverable when its snapshot cannot be written. `OutboxArchiveMigrationIT` checks
the retained-data backfill. `OrderLifecycleKafkaIT` exercises creation over Kafka.

`InventoryStockConsumerIT` covers duplicate/delayed/concurrent snapshots, catalog
edit preservation, transaction rollback, invalid payloads, and real Kafka ->
PostgreSQL -> Product outbox -> Elasticsearch/Redis with already populated caches.
`InventoryCatalogE2EIT` exercises creation, restock, checkout and cancellation
through Gateway and checks the public detail/search stock after each step.

Test execution results and runtime scope must be recorded separately; the presence
of these tests does not by itself establish that a deployment passed them.

## Browser integration fixes

The same validation exposed five integration failures outside the stock event path:

- Gateway now provides a reactive `CorsConfigurationSource` to Spring Security, so
  allowed-origin preflights run before authentication on protected order/admin
  routes. Actual requests still require the existing JWT/role checks; untrusted
  origins are rejected. CORS headers also accompany authentication failures.
- Admin's Vite build maps SockJS's `global` reference to `globalThis`.
- Products supplies the filter model required by its PrimeVue name filter.
- Inventory connects the table paginator to the server page/count. Sorting controls
  were removed because the API currently fixes order by product ID; sorting one
  loaded page would imply an ordering the server does not provide.
- Customer registers PrimeVue's ConfirmationService, required by order detail and
  its cancellation dialog.

See the [dated validation report](../_bmad-output/test-artifacts/inventory-catalog-sync-validation-2026-11-01.md)
for executed checks, browser fixtures and remaining limitations.
