# Shared PostgreSQL outbox delivery

Order, Inventory and Product retain ownership of their event tables, insertion transactions, Avro mapping and scheduler locks. This library owns the common JDBC claim/fence/retention SQL and bounded asynchronous dispatcher. It has no Kafka client, web starter or service schema dependency.

`JdbcOutboxStore` selects at most50 oldest unpublished aggregate heads, using `FOR UPDATE SKIP LOCKED` and a `NOT EXISTS` predecessor predicate across aggregate type/id. A locked, unknown or leased head prevents a later row of the same aggregate from being selected. Independent aggregates can advance. Claiming is a short transaction; Kafka serialization/send/wait occurs after it ends. Acknowledgement and known-failure release compare the claim token. Old owners cannot acknowledge or release a reclaimed delivery.

`OutboxDispatcher` permits at most50 unresolved sends per instance across polls and waits once per batch, bounded by publish-timeout. Successful confirmed sends acknowledge their fence. Confirmed failures release their claim; unknown mappings also increment the unmappable metric. Incomplete or interrupted sends retain their lease, including when a broker acknowledgement arrives after the poll returned. Lease expiry permits replay: this is **at-least-once delivery**, not exactly-once. Consumers must deduplicate stable event IDs. Virtual send tasks capture Micrometer context and scoped correlation/trace/span MDC.

Inventory/Product keep their persisted event_id UUIDs. Order preserves its existing UUID-name identity from the exact `order-outbox-` namespace and persisted row ID through `StableEventIds`; extraction never changes IDs already seen by consumers. Service adapters retain their public Delivery records and choose their existing event-ID storage layout. No migration or table move is needed for this extraction.

Published-only retention deletes at most10,000 rows per pass, using published_at and the configured cutoff. Service schedules retain their original ShedLock names/timing. Unpublished rows are never deleted. Metrics retain their existing names: `robomart.outbox.published`, `robomart.outbox.publish.failed`, `robomart.outbox.publish.timeout`, `robomart.outbox.unmappable`, `robomart.outbox.retention.deleted`.

Configuration remains under outbox.* in each application: publish-timeout10s, claim-duration2m, retention7d by default. Durations must be at least1ms and the claim lease must exceed the batch wait. Kafka producer/Schema Registry bounds and consumer DLT handling remain application responsibilities. Existing Wave7 old-poller drain/cutover requirements still apply; old pollers do not respect claim columns.

Validation includes dispatcher failure/timeout/interruption/permit/MDC tests, actual PostgreSQL cross-replica claims, locked-head skipping, expired-fence protection, stable IDs and bounded published-only retention, plus the existing owning-service outbox integration suites.

## Future CDC option

Debezium's outbox event router could replace polling after a separately designed migration. No Kafka Connect/Debezium component is introduced here. A future change must define WAL/replication-slot permissions and retention, connector offsets and snapshot policy, schema/Avro conversion, partition keys and per-aggregate ordering, stable event identity, duplicate handling, poison-record recovery and failover monitoring.

Stop or fence old pollers before enabling CDC for their rows. Do not run two unfenced publishers and assume event identity alone provides ordering or exactly-once behavior. Test replay after connector/DB failures, reconcile the handover watermark, and retire polling/retention only after verified delivery continuity. Existing service-owned tables need an explicit router/schema contract rather than a silent table rewrite.
