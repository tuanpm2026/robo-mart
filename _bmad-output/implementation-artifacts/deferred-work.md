# Deferred Work

## Deferred from: code review of 1-1-scaffold-monorepo-minimal-development-infrastructure (2026-03-27)

- **common-lib heavyweight dependencies**: spring-boot-starter-web, spring-boot-starter-data-jpa, spring-boot-starter-actuator are compile-scope in common-lib, forcing all consumers to drag in full web server + JPA + actuator. Consider splitting DTOs/exceptions into a lighter module when more services are added.
- **BaseEntity missing @Version**: No optimistic locking field. Add `@Version private Long version` when persistence logic lands in Story 1.2 to prevent lost updates in concurrent scenarios.

## Deferred from: code review of 1-3-implement-elasticsearch-integration-product-sync-via-outbox-pattern (2026-03-28)

- **No distributed lock on outbox polling**: Single-instance deployment for now. Multi-instance requires ShedLock or SELECT FOR UPDATE SKIP LOCKED to prevent duplicate event publishing.
- **Consumer idempotency via eventId not implemented**: Task 7.5 specifies eventId-based dedup, but ES save by product ID is inherently idempotent (same ID overwrites). EventId dedup needed only if side effects beyond indexing are added.
- **Auto-register schemas should be disabled in production**: `auto.register.schemas=true` is convenient for dev but should be `false` in production to prevent accidental schema mutations.
- **Corrupt events retry forever without retry count limit**: Unpublished events that fail to serialize/publish are retried every second indefinitely. Needs a `retry_count` column or max-retry mechanism to skip permanently broken events.
