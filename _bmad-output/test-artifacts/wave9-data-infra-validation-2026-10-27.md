# Wave 9 data infrastructure validation — 2026-10-27

Status: implementation and all local reactor/style/image/E2E verification complete. PR CI and merge are tracked in [PR #47](https://github.com/tuanpm2026/robo-mart/pull/47). Branch `wave9/data-infra`.

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

`./mvnw -B -ntp clean verify -DskipE2ETests` passed all18 reactor modules in7m34s:753 unit+298 IT=1,051 executions, zero failures/errors/skips. The common-lib compatibility harness intentionally repeats36 starter tests; there are1,015 distinct tests. All configured80% module coverage gates passed. Explicit `./mvnw -B -ntp checkstyle:check` passed all18 modules; subsequent `./mvnw -B -ntp install -DskipTests -DskipITs -DskipE2ETests` succeeded (tests intentionally skipped only for packaging image inputs). No forced-fork-exit or mismatched-class coverage warning remained.

The actual repository-root CD/source Product image built successfully, including active NullAway and the new split module inputs. Its packaged plain migration CLI ran with network disabled and the label-verified retained old image volume mounted read-only:eligible0/copied0/identical0/skipped0, matching the independently counted empty volume. All9 rendered Dockerfiles match the shared renderer.

| Module | Unit executions | IT | Line coverage |
| --- | ---: | ---: | ---: |
| api-gateway | 63 | 0 | 98.5% |
| build-support | 1 | 0 | 100.0% |
| cart-service | 45 | 41 | 95.5% |
| common-core | 12 | 0 | 90.9% |
| common-lib | 36 | 0 | n/a (dependency-only facade) |
| common-web-starter | 36 | 0 | 83.3% |
| events | 2 | 5 | n/a |
| inventory-service | 75 | 49 | 94.7% |
| notification-service | 116 | 22 | 93.9% |
| order-service | 180 | 57 | 95.3% |
| outbox-lib | 10 | 6 | 98.0% |
| payment-service | 44 | 34 | 91.0% |
| product-service | 108 | 84 | 94.5% |
| proto | 8 | 0 | n/a |
| security-lib | 17 | 0 | 85.3% |

Final isolated image/E2E evidence follows below; CI/merge evidence is the linked PR record. Operator rollout prerequisites remain in `docs/kafka-8-upgrade.md` and `docs/product-object-storage.md`; deployment is not automated in the user's current environment.


## Final retained-data E2E

`infra/e2e/stack.sh build` built all7 actual production/prebuilt application targets plus the dependency fixtures. `infra/e2e/stack.sh up` reached14 healthy running containers; the one-shot bucket initializer exited0. Retained Kafka reported group.version1/metadata.version3.9-IV0 with0 feature updates; retained realm audience reconciliation reported0 updates. Ownership-guarded probes verified UID1001 and actually memory-mapped nonempty AOT caches in all7 application JVMs.

With JDK25, `infra/e2e/stack.sh test` passed4 real tests (zero failures/errors/skips, Maven1m26s): checkout/refund13.86s, image upload/public read/committed deletion0.358s, and both chaos70.69s. The original60second pending-payment recovery budget remains unchanged. The same five held orders recovered automatically after Payment restart; new synchronous checkout was not needed to trigger them.

Read-only SQL attributed only orders29–35 beyond the captured baseline28. All7 were CANCELLED, payments fully REFUNDED at179.99 each and reservations RELEASED, with exactly7 RESERVE/7 COMMIT/7 RELEASE movements. Every inventory quantity matched the before-test snapshot. Image rows returned to the baseline123, preserving existing external seed-image URLs; the newly uploaded object and cached detail reference were removed. Kafka CLI confirmed all8 application groups are type Consumer and Stable (1–3 members), exercising KIP-848 against retained data.

`infra/e2e/stack.sh down` retains every project volume and stops/removes only this isolated project's containers/network; unrelated ivysport-mysql is preserved. No developer/live-cluster data was changed. The explicit empty-volume CLI result is not presented as a production migration; real nonempty copy/conflict behavior is covered by the separate MinIO integration fixtures.
