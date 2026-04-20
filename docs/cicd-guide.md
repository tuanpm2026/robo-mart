# CI/CD Guide — Robo-Mart

## Tổng quan

Robo-Mart có 4 GitHub Actions workflows độc lập, mỗi cái trigger theo path riêng:

| Workflow | File | Trigger khi nào |
|---|---|---|
| Backend CI | `ci-backend.yml` | Thay đổi trong `backend/**` |
| Frontend CI | `ci-frontend.yml` | Thay đổi trong `frontend/**` |
| Schema Compatibility | `schema-compatibility.yml` | Thay đổi trong `backend/proto/**` hoặc `backend/events/**` |
| CD Deploy | `cd-deploy.yml` | Backend CI pass trên `main` |

**Concurrency guard:** Backend CI, Frontend CI, Schema Compatibility đều cancel run cũ nếu có run mới cùng branch push lên. CD Deploy **không** cancel — deploy đang chạy dở sẽ không bị dừng giữa chừng.

---

## 1. Backend CI

> **File:** `.github/workflows/ci-backend.yml`
> **Runner:** ubuntu-24.04 · **Timeout:** 20 phút
> **Permissions:** `checks: write`, `pull-requests: write` (cần cho dorny/test-reporter)

Chạy tuần tự 6 bước chính, sau đó luôn publish kết quả (`if: always()`).

### Bước 1 — Build (parallel, skip tests)

```
./mvnw install -T 1C -DskipTests -DskipITs -DskipE2ETests
```

Build toàn bộ 13 module của monorepo song song (`-T 1C` = 1 thread/CPU core), **không chạy test**. Mục đích là compile + install artifacts vào local Maven cache để các bước sau dùng lại, tránh compile lại từ đầu. Nếu bước này fail → code không compile được, dừng luôn.

### Bước 2 — Checkstyle

```
./mvnw checkstyle:check
```

Kiểm tra code style theo rule trong `backend/config/checkstyle/checkstyle.xml`. Checkstyle đã được cấu hình chạy tự động ở phase `compile`, bước này gọi explicit để lấy output rõ ràng. Fail nếu có vi phạm style — đảm bảo codebase đồng nhất formatting.

### Bước 3 — Unit Tests + ArchUnit

```
./mvnw test -T 1C -DskipITs -DskipE2ETests
```

Chạy tất cả unit tests song song trên mọi service. Bao gồm:

- **Unit tests thuần** — mock dependencies, không cần external services
- **ArchUnit layer validation** — kiểm tra architectural rules (ví dụ: controller không được gọi thẳng repository, service không import từ controller layer). Đây là "architecture tests" chạy tự động, bắt vi phạm layering ngay trong CI.
- **Pact consumer tests** — `NotificationProductConsumerPactTest`, `NotificationOrderConsumerPactTest` — generate file pact dùng cho bước Contract Tests

`-DskipITs` bỏ qua các class có suffix `IT` (integration tests) để bước này chạy nhanh, không cần Docker/Testcontainers.

### Bước 4 — Integration Tests

```
./mvnw verify -DskipTests -DskipE2ETests
env: TESTCONTAINERS_RYUK_DISABLED=true
```

Chạy các `*IT` class qua **Maven Failsafe plugin**. `-DskipTests` bỏ qua unit tests (đã chạy ở bước 3), chỉ chạy integration tests. Mỗi service spin up các container cần thiết qua Testcontainers:

| Service | Containers được spin up |
|---|---|
| product-service | PostgreSQL + Kafka + Elasticsearch + Redis |
| cart-service | Redis |
| order-service | PostgreSQL |
| inventory-service | PostgreSQL + Redis |
| payment-service | PostgreSQL |
| notification-service | PostgreSQL |

`TESTCONTAINERS_RYUK_DISABLED=true` tắt Ryuk daemon (process cleanup) vì GitHub Actions runner không hỗ trợ tốt — container sẽ tự cleanup khi process kết thúc.

### Bước 5 — Contract Tests (Pact Provider)

```
./mvnw verify -pl :product-service,:order-service -Dit.test=*PactProviderIT -DfailIfNoTests=false
```

Chỉ chạy `*PactProviderIT` trên product-service và order-service. Pact provider tests **đọc file pact** (được commit vào `src/test/resources/pacts/`) và gọi thật vào service đang chạy để verify contract khớp với những gì consumer expect.

Ý nghĩa: nếu team backend thay đổi response format của một endpoint, bước này sẽ fail ngay — báo hiệu có service khác (consumer) đang phụ thuộc vào format cũ. Buộc phải update pact file hoặc backward-compat trước khi merge.

### Bước 6 — OpenAPI Drift Detection

```bash
if [ -d "docs/api" ] && ls docs/api/*.json > /dev/null 2>&1; then
    bash infra/ci/scripts/check-openapi-drift.sh
fi
```

So sánh OpenAPI spec được generate từ code với baseline đã commit vào `docs/api/`. Nếu endpoint thay đổi signature mà không cập nhật baseline → fail. Bước này **tự skip** nếu `docs/api/` chưa có baseline (run đầu tiên), tránh blocking.

### Publish Test Results & Upload Artifacts

```yaml
path: backend/**/target/surefire-reports/TEST-*.xml
      backend/**/target/failsafe-reports/TEST-*.xml
```

`dorny/test-reporter` đọc JUnit XML (`TEST-*.xml`) và tạo GitHub Check Run hiển thị kết quả từng test case trực tiếp trên PR. Pattern `TEST-*.xml` quan trọng — loại bỏ `failsafe-summary.xml` (Maven internal format) không phải JUnit XML, sẽ crash parser nếu include.

Artifacts (`surefire-reports/`, `failsafe-reports/`) được upload và giữ 7 ngày để debug thủ công khi cần.

---

## 2. Frontend CI

> **File:** `.github/workflows/ci-frontend.yml`
> **Runner:** ubuntu-24.04 · **Timeout:** 15 phút

Chạy trên cả `customer-website` (port 5173) và `admin-dashboard` (port 5174) — 2 Vue 3 app trong npm workspace.

### Bước 1 — Type Check

```
npm run type-check  # tsc --noEmit
```

TypeScript compile check không output file. Bắt type error trước khi chạy bất cứ thứ gì — nhanh và rẻ nhất.

### Bước 2 — Lint

```
npm run lint  # oxlint + ESLint + vuejs-accessibility
```

- **oxlint**: Rust-based linter, cực nhanh, bắt các lỗi JS/TS cơ bản
- **ESLint**: Rule phức tạp hơn (Vue-specific, import order...)
- **vuejs-accessibility**: Kiểm tra a11y trong Vue template (alt text, ARIA roles...)

### Bước 3 — Format Check

```
npx prettier --check src/
```

Prettier chạy ở chế độ check (không format), fail nếu có file chưa format. Enforces code formatting nhất quán — dev phải chạy `npm run format` trước khi push.

### Bước 4 — Unit Tests (Vitest)

```
npm run test:unit -- --reporter=verbose --reporter=junit --outputFile=test-results.xml
```

Vitest chạy unit tests, output song song: verbose ra console + JUnit XML vào `test-results.xml`. File XML này được dùng ở bước Publish Test Results.

### Bước 5 — Production Build

```
npm run build  # Vite build
```

Build `dist/` thật sự như lên production. Bắt các lỗi chỉ xuất hiện khi bundle (circular import, missing env var, tree-shaking issue...). Nếu type check pass nhưng build fail → thường là vấn đề Vite config.

### Bước 6 — Accessibility Audit

```bash
npx serve dist &
until curl -sf http://localhost:3000 > /dev/null; do sleep 1; done
npx @axe-core/cli http://localhost:3000 || echo "Accessibility warnings found (informational only)"
```

Serve `dist/` rồi chạy axe-core scan. Hiện tại **non-blocking** (`|| echo`) — chỉ warning, không fail CI. Mục đích là visibility: dev thấy accessibility issue ngay trong CI log mà không bị block merge.

---

## 3. Schema Compatibility

> **File:** `.github/workflows/schema-compatibility.yml`
> **Runner:** ubuntu-24.04 · **Timeout:** 10 phút mỗi job

Gồm 2 job chạy song song, chỉ trigger khi thay đổi `proto/` hoặc `events/`.

### Job 1 — Protobuf Backward Compatibility (buf)

**buf lint**: Kiểm tra style và wire compatibility rules của `.proto` files — tên field, numbering, naming convention theo Protobuf best practices.

**buf breaking** _(chỉ chạy trên PR, không chạy khi push lên main)_:

```
buf breaking --against '.git#branch=main'
```

So sánh proto files của branch hiện tại với `main`. Bắt các breaking change: xóa field, đổi field number, thay đổi type... Chỉ chạy trên PR vì so với main — nếu chạy trên push to main sẽ compare HEAD với chính nó (vô nghĩa).

**Maven build :proto**: Compile `.proto` → Java stubs để verify generated code hợp lệ.

### Job 2 — Avro Schema Backward Compatibility

Spin up stack thật: **Zookeeper → Kafka → Schema Registry** (Confluent 7.7.0) dưới dạng GitHub Actions services.

```
./mvnw install -pl :events -am -DskipTests
./mvnw test -pl :events -Dtest=*SchemaCompatibility* -DfailIfNoTests=false
```

Build module `events` (Avro schemas + generated classes), sau đó chạy compatibility tests: đăng ký schema lên Schema Registry thật và verify backward compatibility. Bắt lỗi khi xóa field required, thay đổi type không tương thích — những thứ sẽ crash consumer khi deploy.

---

## 4. CD Deploy

> **File:** `.github/workflows/cd-deploy.yml`
> **Trigger:** `workflow_run` — chỉ khi Backend CI **success** trên `main`
> **Concurrency:** `deploy-main`, `cancel-in-progress: false`

CD **không** trigger bởi Frontend CI hay Schema Compatibility — chỉ phụ thuộc vào Backend CI pass.

### Job 1 — Build & Push Docker Images (matrix)

Chạy song song cho 7 services:

| Service | Port |
|---|---|
| api-gateway | 8080 |
| product-service | 8081 |
| cart-service | 8082 |
| order-service | 8083 |
| inventory-service | 8084 |
| payment-service | 8086 |
| notification-service | 8087 |

Mỗi service:
1. **Docker Buildx** — multi-platform builder
2. **Login GHCR** — `ghcr.io` dùng `GITHUB_TOKEN` tự động
3. **Tag image** với 2 tag: `main-{short_sha}` (immutable, traceability) và `latest` (chỉ trên main)
4. **Build & push** từ `infra/docker/{service}/Dockerfile`, dùng GitHub Actions cache (`type=gha`) để tăng tốc layer caching

### Job 2 — Deploy to Kubernetes

Chạy sau khi **tất cả 7 images** push xong (`needs: build-and-push`).

```bash
# Apply base config
kubectl apply -f infra/k8s/base/namespace.yml
kubectl apply -f infra/k8s/base/configmap.yml

# Rolling update từng service
for SERVICE in ...; do
    kubectl set image deployment/${SERVICE} ${SERVICE}=${REGISTRY}/${SERVICE}:${IMAGE_TAG} -n robomart
    kubectl rollout status deployment/${SERVICE} -n robomart --timeout=5m
done
```

**Rolling update** — Kubernetes thay thế pods từng cái một, không có downtime. Nếu pod mới unhealthy, rollout tự fail và giữ pod cũ. Mỗi service có timeout 5 phút để rollout hoàn thành.

Secrets (database password, JWT key...) **không** commit vào git, được apply thủ công lên cluster trước — CI chỉ apply namespace, configmap, và deployment specs.

Kết thúc bằng `kubectl get pods/deployments -n robomart` để log trạng thái cuối.

---

## Tóm tắt luồng một PR điển hình

```
Developer mở PR
    │
    ├── backend/** thay đổi?
    │       └── Backend CI chạy (Build → Checkstyle → Unit → Integration → Contract → OpenAPI)
    │               └── pass? → merge vào main → CD Deploy (Docker build → K8s rollout)
    │
    ├── frontend/** thay đổi?
    │       └── Frontend CI chạy (Type-check → Lint → Format → Unit → Build → A11y)
    │
    └── proto/** hoặc events/** thay đổi?
            └── Schema Compatibility chạy (buf lint + buf breaking · Avro compat)
```

Mỗi workflow độc lập — thay đổi frontend không trigger backend CI và ngược lại, tránh tốn thời gian chờ không cần thiết.
