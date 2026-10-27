# Common core

Shared DTOs, exception contracts, audit contracts and the legacy JPA base-entity API. No Boot starter, web runtime, database pool or Spring component scan is exported. Hibernate is optional solely to compile the existing mapped base class; persistence services explicitly supply their own JPA starter. DTO/error/audit packages retain their Java names and wire shape.

`ErrorCodeValue.name()` is the stable external code contract. Shared errors retain generic `ErrorCode` values; each service defines domain enums. Core packages remain JSpecify null-marked and production compilation runs NullAway.
