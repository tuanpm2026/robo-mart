# Deferred Work

## Deferred from: code review of 1-1-scaffold-monorepo-minimal-development-infrastructure (2026-03-27)

- **common-lib heavyweight dependencies**: spring-boot-starter-web, spring-boot-starter-data-jpa, spring-boot-starter-actuator are compile-scope in common-lib, forcing all consumers to drag in full web server + JPA + actuator. Consider splitting DTOs/exceptions into a lighter module when more services are added.
- **BaseEntity missing @Version**: No optimistic locking field. Add `@Version private Long version` when persistence logic lands in Story 1.2 to prevent lost updates in concurrent scenarios.

## Deferred from: code review of 1-3-implement-elasticsearch-integration-product-sync-via-outbox-pattern (2026-03-28)

- **No distributed lock on outbox polling**: Single-instance deployment for now. Multi-instance requires ShedLock or SELECT FOR UPDATE SKIP LOCKED to prevent duplicate event publishing.
- **Consumer idempotency via eventId not implemented**: Task 7.5 specifies eventId-based dedup, but ES save by product ID is inherently idempotent (same ID overwrites). EventId dedup needed only if side effects beyond indexing are added.
- **Auto-register schemas should be disabled in production**: `auto.register.schemas=true` is convenient for dev but should be `false` in production to prevent accidental schema mutations.
- **Corrupt events retry forever without retry count limit**: Unpublished events that fail to serialize/publish are retried every second indefinitely. Needs a `retry_count` column or max-retry mechanism to skip permanently broken events.

## Deferred from: code review of 1-4-implement-product-search-with-full-text-filtering (2026-03-28)

- **GlobalExceptionHandler missing HandlerMethodValidationException handler**: Validation errors from @ModelAttribute @Valid may return 500 instead of 400 in Spring Framework 7. Need to add handler for HandlerMethodValidationException.
- **No error handling when Elasticsearch is unavailable**: ProductSearchService.search() does not catch ES connection errors. Should wrap in try-catch and throw ExternalServiceException (503) or implement circuit breaker (Epic 8 scope).
- **No sort for match_all queries**: When no keyword is provided, match_all returns results in non-deterministic order, which can cause inconsistent pagination.
- **No relevance ranking test**: AC #1 specifies relevance ranking with name boost x3 > brand boost x2, but no test verifies ordering.
- **No performance test for 500ms p95 SLA**: AC #6 requires search p95 < 500ms but no load testing artifact exists.
- **Sort parameters from Pageable not validated**: Sort params from query string are passed directly to ES without whitelisting. Invalid sort fields could cause ES errors.
