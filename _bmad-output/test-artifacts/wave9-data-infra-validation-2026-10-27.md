# Wave 9 data infrastructure validation — 2026-10-27

Status: implementation complete; final verification/PR/merge in progress. Branch `wave9/data-infra`.

## Changes and operational boundaries

- `outbox-lib` owns aggregate-head eligibility, SKIP LOCKED claims, fenced acknowledgements/releases, bounded asynchronous delivery, stable identity derivation and published-only retention. Service Avro mapping, schema ownership and scheduler locks remain local. No new migration or event-ID rewrite. Debezium is documented as a future handover option; Connect is not added.
- Confluent serializers/broker/registry move to 8.2.4, matching Boot's Kafka client 4.2.2. Multi-platform container digests are pinned. Existing KRaft identity/quorum/metadata are preserved. Production stays `classic` until an explicit operator check; disposable fixtures and isolated E2E use `consumer` with group.version=1.
- Product uses AWS SDK v2 2.57.4 for S3-compatible image storage/public URLs. Rollback/after-commit cleanup and cache semantics remain intact. Legacy URLs redirect after an explicit conditional copy/checksum migration. Old volumes/PVCs are retained read-only. Local MinIO is built from the pinned patched security source; production requires an operator-provisioned maintained S3 service/secret/public delivery.
- `common-core` and conditional `common-web-starter` replace services' old common dependency. Generic exceptions accept domain-owned wire codes. Cart no longer excludes database auto-configuration, and its real context asserts no Hibernate, datasource, entity manager or Flyway. Hibernate is optional for the legacy base-entity API; test-support's JPA dependency is optional too. NullAway remains active in both owning common modules and security-lib.
- `common-lib` remains a dependency-only compatibility facade because the existing CI library selector names it. Its test source references the owning starter tests, exercising them in that CI job without duplicated source files or workflow edits. Local reactor gates also verify both owning modules independently. Test totals must identify that intentional repeated execution.
- Registry8 and Springdoc required explicit alignment of Jakarta Swagger annotations to 2.2.55; no dependency-convergence gate was disabled.

## Executed focused evidence

All Maven commands use JDK25 and `backend/mvnw`. IDE background compilation was suspended during Maven to prevent target-class/JaCoCo corruption. Docker IT processes were serialized.

- `./mvnw -B -ntp clean verify -pl :outbox-lib -DskipE2ETests`: 10 unit +6 PostgreSQL IT, coverage passed (held-row SKIP LOCKED, fencing, replay identity and 10,001-row retention fixture).
- Product/Order/Inventory unit suites: 358 tests passed before shared extraction follow-ups. Full Order/Inventory verification: 180+57 and 75+49, coverage passed. A deliberately partial Order IT selection passed assertions but failed its 76% module coverage gate; the subsequent full module suite passed without weakening that gate.
- `./mvnw -B -ntp clean verify -pl :events -DskipE2ETests`: 2 unit +5 real Kafka/Registry IT, including all13 Avro schemas and classic-to-consumer committed-offset continuity. An initial old substring ID parser failed on Registry8's extended response; proper JSON parsing and old/new response regressions replaced it.
- Full Notification verification on Confluent8.2.4/consumer: 116 unit +22 IT, coverage passed.
- Retained isolated broker upgrade: all17 topic IDs/partition counts,4 committed partition offsets,6 schema IDs/versions/content, cluster/node/directory identity preserved. metadata.version remains3.9-IV0 and kraft.version0. After guarded dry-run, group.version0→1 preserved the same snapshot. No volume formatting/deletion.
- `python3 -m unittest discover -s infra/e2e -p 'test_configure_kafka.py'`: 5 guard tests passed.
- S3 Product first full suite: 100 unit +84 IT; one migration HTTP assertion failed because the client followed302 automatically. All upload/rollback/delete assertions passed. The corrected non-following redirect test then passed both migration IT in the whole reactor.
- `./mvnw -B -ntp clean verify -pl :common-core,:common-web-starter,:common-lib -DskipITs -DskipE2ETests`: all3 modules passed after moving tests to owning modules and adding domain-code/auto-configuration regressions. Earlier owning-module coverage shortfalls were corrected through real contract tests, with80% gates unchanged.
- Local MinIO source Docker build passed. Compose bucket bootstrap and repeat both exited0; anonymous bucket listing and object PUT both returned403. The retained E2E image volume has0 files; actual copy/idempotence/conflict/source-preservation evidence uses separate real MinIO test fixtures.
- Both Compose renders, generated Dockerfile drift check, shell syntax, and Kubernetes base/production/ingress-tls renders passed. No live cluster was applied. A nonexistent staging-overlay path was rejected during inspection; the repository's two actual overlays were rendered instead.
- Proto `buf lint` and `buf breaking --against '../../.git#branch=main,subdir=backend/proto'` passed.
- Frontend: workspace type-check, unit tests (240 customer +87 admin=327), builds and read-only Prettier checks passed. No frontend source changes.

## Final gates

Full reactor, final image/E2E checks, CI and merge results will be recorded here after completion. Operator rollout prerequisites remain in `docs/kafka-8-upgrade.md` and `docs/product-object-storage.md`; deployment is not automated in the user's current environment.
