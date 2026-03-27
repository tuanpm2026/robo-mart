# Deferred Work

## Deferred from: code review of 1-1-scaffold-monorepo-minimal-development-infrastructure (2026-03-27)

- **common-lib heavyweight dependencies**: spring-boot-starter-web, spring-boot-starter-data-jpa, spring-boot-starter-actuator are compile-scope in common-lib, forcing all consumers to drag in full web server + JPA + actuator. Consider splitting DTOs/exceptions into a lighter module when more services are added.
- **BaseEntity missing @Version**: No optimistic locking field. Add `@Version private Long version` when persistence logic lands in Story 1.2 to prevent lost updates in concurrent scenarios.
