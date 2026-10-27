# Wave 8 platform validation — 2026-10-27

Branch: `wave8/platform`, based on Wave7 and rebased over the two subsequent main image-tag commits. Implementation decisions and intermediate investigations remain in [the remediation record](../../docs/backend-remediation-waves-6-9.md). PR CI/merge status will be added after execution.

## Final verification

All Maven commands used JDK25 and `backend/mvnw`. Background IDE compilation was suspended during Maven compilation/testing and resumed afterwards. Testcontainers modules ran sequentially on the8GiB Docker engine; the full E2E stack did not run concurrently with them.

`./mvnw -B -ntp clean verify -DskipE2ETests` passed all15 modules in6m35s after the final search-fallback fix: **698 unit +287 integration tests =985**, zero failures/errors/skips. Every configured coverage gate passed, including configuration/security code. There were no forced-fork-exit or Boot3 introspection warnings.

Counts agree between Maven execution summaries and actual XML testcase elements. Some nested JUnit XML suite headers undercount their testcase elements; counts do not rely only on those root attributes. No raw test XML properties or credentials are included here.

| Module | Unit | Integration |
| --- | ---: | ---: |
| Build Support | 1 | 0 |
| Common | 44 | 0 |
| Security | 17 | 0 |
| Proto | 8 | 0 |
| Events | 2 | 3 |
| Product | 103 | 82 |
| Cart | 45 | 40 |
| Gateway | 63 | 0 |
| Order | 180 | 57 |
| Inventory | 75 | 49 |
| Payment | 44 | 34 |
| Notification | 116 | 22 |

`./mvnw -B -ntp checkstyle:check` passed all15 modules. Checkstyle12.3.1 also runs during validate; existing advisory severity remains unchanged. `./mvnw -B -ntp install -DskipTests -DskipITs -DskipE2ETests` subsequently installed the exact dependencies with IDE compilation still paused. That installation intentionally skipped tests and is not a separate test result.

Proto `buf lint` and `buf breaking --against '../../.git#branch=main,subdir=backend/proto'` passed. All3 real Kafka/Schema Registry Avro compatibility IT passed in the reactor. No proto/Avro business schema changed.

## Regression evidence

- Real MVC tests cover unreadable JSON, missing headers, type mismatch,403/405/415, preserved Allow header and the legacy error envelope within RFC9457 responses.
- Real HTTP/gRPC probes assert virtual execution. A final real HTTP fallback regression forces an Elasticsearch connection failure, asserts no DB transaction during that I/O, and reads category/lazy image DTO fields inside the subsequent read-only PostgreSQL transaction with OSIV disabled.
- Actual Kafka scopes carry distinct and then absent baggage per record, with no leaked MDC; configured producer interceptors remain active. DLT tests preserve Avro/raw bytes and source metadata. Terminal archive failure retries twice and then persists rather than acknowledging a lost record.
- Real signed JWTs/local JWK server verify issuer, audience, expiry and signature failures, valid-token acceptance and lazy JWK retrieval.
- Production gRPC proxies verify bounded retry counts, business-error classification, fallback ordering and zero calls through OPEN. Provider concurrency tests share2 permits across charge/refund, reject overload and reuse permits after exceptions.
- Gateway transport tests cover OPEN/half-open recovery, unchanged upstream502 body and an order response longer than1second without the library's implicit TimeLimiter. The reactive-only regression verifies no blocking factory and working Boot4 circuit metrics.
- NullAway is an ERROR-level production compiler gate in marked Common/Security packages. A temporary source deliberately returning null failed compilation with NullAway; it was removed and positive clean verification passed. No blanket nullability suppression was added.

## Images and configuration

`python3 backend/scripts/render-dockerfiles.py --check` passed all9 generated entry points. The source/CD/prebuilt contexts and targets are preserved without workflow edits.

Executed successfully:

- `docker build --progress=plain -f backend/Dockerfile --target api-gateway -t robo-w8-all-source-gateway backend` — all15 source modules packaged, including Linux proto/gRPC generation.
- `docker build --progress=plain -f infra/docker/product-service/Dockerfile -t robo-w8-product-source .` — repeated successfully after the final Product fix.
- `docker build --progress=plain -f infra/docker/api-gateway/Dockerfile -t robo-w8-gateway-cd .` — repeated successfully after the reactive-adapter correction.
- `infra/e2e/stack.sh build` — all7 actual prebuilt production application targets built serially, along with the isolated PostgreSQL/Keycloak fixtures. The final Product target was rebuilt again after its fix.

Every image creates its AOT cache with BuildKit networking disabled, the exact pinned runtime JRE and actual extracted classpath. Training starts only an isolated aot-training core context: no application scan, auto-configuration, migrations, consumers, schedulers, seeders or outbound clients. Its configuration is excluded from normal app scans by profile. This warms core Spring only and makes no quantified startup-performance claim.

An actual Gateway main smoke loaded AOT-linked classes and reached liveness UP in a512MiB container with192MiB heap. Read-only probes of all7 running E2E JVMs verified UID1001 and a nonempty, memory-mapped application.aot. Probes resolve Java below Compose's init process rather than assuming PID1 is Java.

Compose core+app, isolated E2E, and base+full monitoring overlay config checks passed. Kustomize rendered base, production and ingress-tls successfully. Renderer compilation and shell syntax checks passed. No cluster was changed.

## Isolated persisted-data E2E

Commands: `infra/e2e/stack.sh up`, `infra/e2e/stack.sh test`, guarded read-only SQL, `infra/e2e/stack.sh down`.

All13 containers became healthy. **All3 real E2E/chaos tests passed**, zero failures/errors/skips: full flow9.618s, both chaos65.49s, Maven1m16s. Five original orders created during Payment outage recovered automatically within the unchanged60second recovery budget, with the same keys and no duplicate charges. The full flow verifies search, carts, checkout, asynchronous confirmation, refund cancellation, service-authenticated audit/reconciliation; latency injection also passed.

The retained isolated realm lacked the earlier Inventory fixture client. The audience-only guard stopped before starting apps. A separate one-time provisioner verified exact Compose project/service labels and the loopback Keycloak port, created only that missing fixture client/intended grant, and preserved existing identities/secrets/data. Normal mapper reconciliation then completed; repeat startup reported0 updates. The committed reconciler remains audience-only and refuses missing clients.

Both service identities received canonical-public-issuer/API-audience tokens with exactly their intended role. Inventory's signed token received200 from the minimal internal order-status endpoint and403 from admin order listing. No production realm, cluster or credential was changed.

The first E2E attempt failed before creating orders: the stack lacked its Elasticsearch URI, and OSIV=false exposed detached lazy images in PostgreSQL fallback. Fast polling obscured the original500 with rate-limit429. The fallback transaction, explicit container URI and3second polling were corrected; the45second search budget and production rate limits were unchanged.

Post-success SQL attributed only orders22–28 beyond baseline21:

- 7 CANCELLED orders;
- 7 fully REFUNDED payments, amount=refunded_amount=179.99 each;
- 7 RELEASED reservations;
- exactly7 RESERVE,7 COMMIT and7 RELEASE movements.

Teardown removed only this project's containers/network. Every data volume was retained; unrelated ivysport-mysql remained running. No earlier order was removed.

## Frontend, rollout and limits

Both apps passed workspace type-check, unit tests (Customer240 +Admin87), production builds and read-only Prettier checks, on the installed lockfile dependency graph. No frontend source changed in this wave.

No new Flyway migration. Existing deployed realms need the API audience mapper before enabling enforcement, preserving the canonical public issuer and independent private JWK URI. Host dev profiles are explicit. Anonymous rate limiting uses the socket peer; behind ingress, anonymous users share that peer bucket until trusted proxy ranges are deliberately configured.

The user confirmed auto-deploy is disabled. This work prepares code/configuration and the authorized PR merge, not a live deployment. AOT caches must be rebuilt with application/JRE/architecture changes. External email delivery is not exercised: the dev profile uses the configured mock email service. Performance/load testing is outside these functional gates.
