# Story 9.2: Implement Health Checks & Centralized Configuration

Status: in-progress

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a DevOps engineer,
I want health check endpoints on every service and centralized configuration management,
So that K8s can manage pod lifecycle and all services are consistently configured.

## Acceptance Criteria

1. **Given** Spring Boot Actuator on every service
   **When** `/actuator/health/liveness` is called
   **Then** it returns service process status within 1 second (FR60, NFR41)

2. **Given** `/actuator/health/readiness` on every service
   **When** called
   **Then** it validates: database connected, Kafka connected, Redis reachable (where applicable), custom health indicators per dependency — returns UP only when all dependencies are ready (FR60, NFR30)

3. **Given** Micrometer + Prometheus endpoint
   **When** `/actuator/prometheus` is scraped
   **Then** it exposes: request rate, error rate, p50/p95/p99 latency, active connections, Kafka consumer lag (NFR42)

4. **Given** service configuration
   **When** managed via `application.yml` per profile (dev, demo, test)
   **Then** each service is consistently configured without Config Server — K8s ConfigMaps/Secrets handle production overrides (FR62)

5. **Given** K8s manifests in `infra/k8s/`
   **When** inspected
   **Then** each service has: `deployment.yml` (with liveness/readiness probes, resource limits 256Mi/250m request, 512Mi/500m limit), `service.yml`, `hpa.yml` (NFR25, NFR29, NFR30)

## Tasks / Subtasks

### Part A: Enable K8s Health Probes in All 7 Services (AC1, AC2)

#### Task 1: Add Health Probe Config to All 7 Service `application.yml` Files (AC1, AC2)

- [x] **Files**: `backend/api-gateway/src/main/resources/application.yml`, `product-service/application.yml`, `cart-service/application.yml`, `order-service/application.yml`, `inventory-service/application.yml`, `payment-service/application.yml`, `notification-service/application.yml`
- [x] **Read each file first** to identify the existing `management:` block (base section before any `---` profile separator)
- [x] Add `management.health.probes.enabled: true` and readiness group config to the BASE section of each service:

  ```yaml
  management:
    health:
      probes:
        enabled: true
    endpoint:
      health:
        show-details: always
        group:
          liveness:
            include: livenessState
          readiness:
            include: {service-specific — see table below}
  ```

- [x] Readiness group per service:

  | Service | `readiness.include` value |
  |---------|--------------------------|
  | api-gateway | `readinessState,redis` |
  | product-service | `readinessState,db,redis,elasticsearch,kafka` |
  | cart-service | `readinessState,redis,kafka` |
  | order-service | `readinessState,db,kafka` |
  | inventory-service | `readinessState,db,redis,kafka` |
  | payment-service | `readinessState,db,kafka` |
  | notification-service | `readinessState,db,kafka` |

  > **Why api-gateway includes `redis`**: api-gateway uses Redis for rate limiting (Story 8.3). If Redis is unreachable, rate-limiting is non-functional and the gateway should not accept traffic.

- [x] **product-service special case**: Its dev profile already has `management.health.elasticsearch.enabled: false` — leave it as-is. The base config readiness group includes `elasticsearch` (for non-dev environments), but the dev profile disables the ES health indicator, so ES will not appear in dev readiness checks.

- [x] Ensure `management.endpoints.web.exposure.include` in base config already contains `health` (already present in all services — do not duplicate, just verify)

- [x] **IMPORTANT**: These additions go in the BASE config section (before the first `---`), NOT inside any profile block. Otherwise they only apply when that profile is active.

- [x] **IMPORTANT**: `management.endpoint.health` (singular) is separate from `management.endpoints.web.exposure` (plural — for exposing). Do not confuse them.

- [x] **api-gateway special case**: Its current config has `management.endpoint.health.show-details: when-authorized`. Change this to `always` (needed for K8s probes to return detail, and the endpoint is already behind the gateway RBAC at the infra level).

---

### Part B: Ensure p50/p95/p99 Percentile Metrics in All Services (AC3)

#### Task 2: Add Histogram Percentile Config to Base Section of All 7 Services (AC3)

- [x] **Files**: same 7 service `application.yml` files
- [x] **Read each file first** to check if `management.metrics.distribution.percentiles` already exists in base (non-profile) section
- [x] **Current state**:
  - `api-gateway` base already has `percentiles: "[http.server.requests]": 0.95` — **update to include 0.5, 0.95, 0.99**
  - `cart-service` base already has `percentiles: "[http.server.requests]": 0.95` — **update to include 0.5, 0.95, 0.99**
  - `product-service` base does NOT have it (dev profile has 0.95 only) — **add to base**
  - `notification-service` dev profile has `percentiles: "[http.server.requests]": 0.95` — **add to base, leave dev profile as-is**
  - For order-service, inventory-service, payment-service: **read first, then add to base**
- [x] Add/update to base `management:` block of each service:
  ```yaml
  management:
    metrics:
      distribution:
        percentiles-histogram:
          "[http.server.requests]": true
        percentiles:
          "[http.server.requests]": 0.5,0.95,0.99
  ```
- [x] `percentiles-histogram: true` enables Prometheus histogram buckets needed for accurate percentile calculation at query time
- [x] Kafka consumer lag is auto-exposed by Spring Kafka 4.x Micrometer integration when `spring.kafka.listener.observation-enabled: true` is active (already configured in Story 9.1 for all Kafka consumer services). No additional config needed.

---

### Part C: K8s Base Manifests (AC4, AC5)

#### Task 3: Create `infra/k8s/base/namespace.yml` (AC5)

- [x] **File**: `infra/k8s/base/namespace.yml`
  ```yaml
  apiVersion: v1
  kind: Namespace
  metadata:
    name: robomart
    labels:
      name: robomart
  ```

#### Task 4: Create `infra/k8s/base/configmap.yml` (AC4, AC5)

- [x] **File**: `infra/k8s/base/configmap.yml`
- [ ] Contains all non-sensitive shared config overrides. Spring Boot's relaxed binding maps env vars to properties:
  - `SPRING_KAFKA_BOOTSTRAP_SERVERS` → `spring.kafka.bootstrap-servers`
  - `SPRING_DATA_REDIS_HOST` → `spring.data.redis.host`
  - etc.
  ```yaml
  apiVersion: v1
  kind: ConfigMap
  metadata:
    name: robomart-config
    namespace: robomart
  data:
    # Kafka
    SPRING_KAFKA_BOOTSTRAP_SERVERS: "kafka:29092"
    KAFKA_BOOTSTRAP_SERVERS: "kafka:29092"
    SPRING_KAFKA_PROPERTIES_SCHEMA_REGISTRY_URL: "http://schema-registry:8085"
    SCHEMA_REGISTRY_URL: "http://schema-registry:8085"
    # Redis
    SPRING_DATA_REDIS_HOST: "redis"
    SPRING_DATA_REDIS_PORT: "6379"
    # Elasticsearch
    SPRING_ELASTICSEARCH_URIS: "http://elasticsearch:9200"
    # Keycloak / JWT
    SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI: "http://keycloak:8180/realms/robomart"
    KEYCLOAK_JWK_SET_URI: "http://keycloak:8180/realms/robomart/protocol/openid-connect/certs"
    # Tracing
    MANAGEMENT_OTLP_TRACING_ENDPOINT: "http://tempo:4318/v1/traces"
    TRACING_SAMPLE_RATE: "0.1"
    # Actuator — ensure prometheus endpoint is exposed even when dev profile reduces it
    MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE: "health,info,metrics,prometheus"
    # Service-to-service URLs (used by notification-service and api-gateway routing)
    ORDER_SERVICE_URL: "http://order-service:8083"
    PRODUCT_SERVICE_URL: "http://product-service:8081"
    CART_SERVICE_URL: "http://cart-service:8082"
    INVENTORY_SERVICE_URL: "http://inventory-service:8084"
    PAYMENT_SERVICE_URL: "http://payment-service:8086"
    NOTIFICATION_SERVICE_URL: "http://notification-service:8087"
    API_GATEWAY_URL: "http://api-gateway:8080"
    # api-gateway downstream routes
    GATEWAY_SERVICES_PRODUCT_SERVICE: "http://product-service:8081"
    GATEWAY_SERVICES_CART_SERVICE: "http://cart-service:8082"
    GATEWAY_SERVICES_ORDER_SERVICE: "http://order-service:8083"
    GATEWAY_SERVICES_INVENTORY_SERVICE: "http://inventory-service:8084"
    GATEWAY_SERVICES_PAYMENT_SERVICE: "http://payment-service:8086"
    GATEWAY_SERVICES_NOTIFICATION_SERVICE: "http://notification-service:8087"
  ```

#### Task 5: Create `infra/k8s/base/secrets-template.yml` (AC4, AC5)

- [x] **File**: `infra/k8s/base/secrets-template.yml`
- [x] **WARNING**: Template only — replace `REPLACE_ME` values with real base64-encoded secrets before applying. Use `echo -n "value" | base64`
- [x] Uses `stringData` (plain text, K8s encodes automatically) for easier maintenance:
  ```yaml
  # K8s Secrets Template — DO NOT commit real passwords
  # Apply: kubectl apply -f secrets-template.yml -n robomart
  # Each service's datasource credentials
  ---
  apiVersion: v1
  kind: Secret
  metadata:
    name: product-db-secret
    namespace: robomart
  type: Opaque
  stringData:
    SPRING_DATASOURCE_URL: "jdbc:postgresql://product-postgres:5432/product_db"
    SPRING_DATASOURCE_USERNAME: "robomart"
    SPRING_DATASOURCE_PASSWORD: "REPLACE_ME"
  ---
  apiVersion: v1
  kind: Secret
  metadata:
    name: order-db-secret
    namespace: robomart
  type: Opaque
  stringData:
    SPRING_DATASOURCE_URL: "jdbc:postgresql://order-postgres:5432/order_db"
    SPRING_DATASOURCE_USERNAME: "robomart"
    SPRING_DATASOURCE_PASSWORD: "REPLACE_ME"
  ---
  apiVersion: v1
  kind: Secret
  metadata:
    name: inventory-db-secret
    namespace: robomart
  type: Opaque
  stringData:
    SPRING_DATASOURCE_URL: "jdbc:postgresql://inventory-postgres:5432/inventory_db"
    SPRING_DATASOURCE_USERNAME: "robomart"
    SPRING_DATASOURCE_PASSWORD: "REPLACE_ME"
  ---
  apiVersion: v1
  kind: Secret
  metadata:
    name: payment-db-secret
    namespace: robomart
  type: Opaque
  stringData:
    SPRING_DATASOURCE_URL: "jdbc:postgresql://payment-postgres:5432/payment_db"
    SPRING_DATASOURCE_USERNAME: "robomart"
    SPRING_DATASOURCE_PASSWORD: "REPLACE_ME"
  ---
  apiVersion: v1
  kind: Secret
  metadata:
    name: notification-db-secret
    namespace: robomart
  type: Opaque
  stringData:
    SPRING_DATASOURCE_URL: "jdbc:postgresql://notification-postgres:5432/notification_db"
    SPRING_DATASOURCE_USERNAME: "postgres"
    SPRING_DATASOURCE_PASSWORD: "REPLACE_ME"
  ```
- [x] `notification-service` uses `${DB_USER:postgres}` env var (not `SPRING_DATASOURCE_USERNAME`) — add `DB_USER` and `DB_PASSWORD` keys to `notification-db-secret` in addition to `SPRING_DATASOURCE_*`

---

### Part D: K8s Manifests — api-gateway (AC5)

#### Task 6: Create K8s Manifests for `api-gateway`

- [x] **File**: `infra/k8s/services/api-gateway/deployment.yml`
  ```yaml
  apiVersion: apps/v1
  kind: Deployment
  metadata:
    name: api-gateway
    namespace: robomart
    labels:
      app: api-gateway
  spec:
    replicas: 2
    selector:
      matchLabels:
        app: api-gateway
    template:
      metadata:
        labels:
          app: api-gateway
      spec:
        containers:
        - name: api-gateway
          image: ghcr.io/robomart/api-gateway:latest
          ports:
          - containerPort: 8080
            name: http
          resources:
            requests:
              memory: "256Mi"
              cpu: "250m"
            limits:
              memory: "512Mi"
              cpu: "500m"
          envFrom:
          - configMapRef:
              name: robomart-config
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
            timeoutSeconds: 5
            failureThreshold: 3
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 20
            periodSeconds: 5
            timeoutSeconds: 3
            failureThreshold: 3
  ```
- [x] **File**: `infra/k8s/services/api-gateway/service.yml`
  ```yaml
  apiVersion: v1
  kind: Service
  metadata:
    name: api-gateway
    namespace: robomart
  spec:
    selector:
      app: api-gateway
    ports:
    - port: 8080
      targetPort: 8080
      protocol: TCP
      name: http
    type: LoadBalancer
  ```
  > api-gateway is the only LoadBalancer service — all others use ClusterIP
- [x] **File**: `infra/k8s/services/api-gateway/hpa.yml`
  ```yaml
  apiVersion: autoscaling/v2
  kind: HorizontalPodAutoscaler
  metadata:
    name: api-gateway
    namespace: robomart
  spec:
    scaleTargetRef:
      apiVersion: apps/v1
      kind: Deployment
      name: api-gateway
    minReplicas: 2
    maxReplicas: 5
    metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
  ```

---

### Part E: K8s Manifests — product-service (AC5)

#### Task 7: Create K8s Manifests for `product-service`

- [x] **File**: `infra/k8s/services/product-service/deployment.yml`
  ```yaml
  apiVersion: apps/v1
  kind: Deployment
  metadata:
    name: product-service
    namespace: robomart
    labels:
      app: product-service
  spec:
    replicas: 2
    selector:
      matchLabels:
        app: product-service
    template:
      metadata:
        labels:
          app: product-service
      spec:
        containers:
        - name: product-service
          image: ghcr.io/robomart/product-service:latest
          ports:
          - containerPort: 8081
            name: http
          resources:
            requests:
              memory: "256Mi"
              cpu: "250m"
            limits:
              memory: "512Mi"
              cpu: "500m"
          envFrom:
          - configMapRef:
              name: robomart-config
          - secretRef:
              name: product-db-secret
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8081
            initialDelaySeconds: 30
            periodSeconds: 10
            timeoutSeconds: 5
            failureThreshold: 3
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8081
            initialDelaySeconds: 20
            periodSeconds: 5
            timeoutSeconds: 3
            failureThreshold: 3
  ```
- [x] **File**: `infra/k8s/services/product-service/service.yml`
  ```yaml
  apiVersion: v1
  kind: Service
  metadata:
    name: product-service
    namespace: robomart
  spec:
    selector:
      app: product-service
    ports:
    - port: 8081
      targetPort: 8081
      protocol: TCP
      name: http
    type: ClusterIP
  ```
- [x] **File**: `infra/k8s/services/product-service/hpa.yml` — same structure as api-gateway HPA but name: `product-service`, same minReplicas: 2, maxReplicas: 5, cpu: 70%

---

### Part F: K8s Manifests — cart-service (AC5)

#### Task 8: Create K8s Manifests for `cart-service`

- [x] **File**: `infra/k8s/services/cart-service/deployment.yml`
  - containerPort: 8082
  - Resources: 256Mi/250m → 512Mi/500m
  - `envFrom`: `configMapRef: robomart-config` (no DB Secret — Redis only, Redis host comes from ConfigMap)
  - livenessProbe: `/actuator/health/liveness` port 8082, initialDelay 30s, period 10s, timeout 5s
  - readinessProbe: `/actuator/health/readiness` port 8082, initialDelay 20s, period 5s, timeout 3s
- [x] **File**: `infra/k8s/services/cart-service/service.yml` — ClusterIP, port 8082
- [x] **File**: `infra/k8s/services/cart-service/hpa.yml` — name: `cart-service`, minReplicas: 2, maxReplicas: 5, cpu: 70%

---

### Part G: K8s Manifests — order-service (AC5)

#### Task 9: Create K8s Manifests for `order-service`

- [x] **File**: `infra/k8s/services/order-service/deployment.yml`
  - containerPorts: 8083 (http) AND 9093 (grpc)
  - Resources: 256Mi/250m → 512Mi/500m
  - `envFrom`: `[configMapRef: robomart-config, secretRef: order-db-secret]`
  - Additional env var: `GRPC_SERVER_PORT: "9093"` (if not already defaulting correctly)
  - livenessProbe: `/actuator/health/liveness` port 8083, initialDelay 45s (gRPC init + DB pool + Saga setup takes longer), period 10s, timeout 5s, failureThreshold 3
  - readinessProbe: `/actuator/health/readiness` port 8083, initialDelay 30s, period 5s, timeout 3s
- [x] **File**: `infra/k8s/services/order-service/service.yml` — ClusterIP, **two ports**:
  ```yaml
  ports:
  - port: 8083
    targetPort: 8083
    name: http
  - port: 9093
    targetPort: 9093
    name: grpc
  ```
- [x] **File**: `infra/k8s/services/order-service/hpa.yml` — name: `order-service`, minReplicas: 2, maxReplicas: 5, cpu: 70%

---

### Part H: K8s Manifests — inventory-service (AC5)

#### Task 10: Create K8s Manifests for `inventory-service`

- [x] **File**: `infra/k8s/services/inventory-service/deployment.yml`
  - containerPorts: 8084 (http) AND 9094 (grpc)
  - Resources: 256Mi/250m → 512Mi/500m
  - `envFrom`: `[configMapRef: robomart-config, secretRef: inventory-db-secret]`
  - livenessProbe: `/actuator/health/liveness` port 8084, initialDelay 40s, period 10s, timeout 5s
  - readinessProbe: `/actuator/health/readiness` port 8084, initialDelay 25s, period 5s, timeout 3s
- [x] **File**: `infra/k8s/services/inventory-service/service.yml` — ClusterIP, ports: 8084 (http) + 9094 (grpc)
- [x] **File**: `infra/k8s/services/inventory-service/hpa.yml` — name: `inventory-service`, min 2, max 5, cpu 70%

---

### Part I: K8s Manifests — payment-service (AC5)

#### Task 11: Create K8s Manifests for `payment-service`

- [x] **File**: `infra/k8s/services/payment-service/deployment.yml`
  - containerPorts: 8086 (http) AND 9095 (grpc)
  - Resources: 256Mi/250m → 512Mi/500m
  - `envFrom`: `[configMapRef: robomart-config, secretRef: payment-db-secret]`
  - livenessProbe: `/actuator/health/liveness` port 8086, initialDelay 35s, period 10s, timeout 5s
  - readinessProbe: `/actuator/health/readiness` port 8086, initialDelay 20s, period 5s, timeout 3s
- [x] **File**: `infra/k8s/services/payment-service/service.yml` — ClusterIP, ports: 8086 (http) + 9095 (grpc)
- [x] **File**: `infra/k8s/services/payment-service/hpa.yml` — name: `payment-service`, min 2, max 5, cpu 70%

---

### Part J: K8s Manifests — notification-service (AC5)

#### Task 12: Create K8s Manifests for `notification-service`

- [x] **File**: `infra/k8s/services/notification-service/deployment.yml`
  - containerPort: 8087 (http only — no gRPC)
  - Resources: 256Mi/250m → 512Mi/500m
  - `envFrom`: `[configMapRef: robomart-config, secretRef: notification-db-secret]`
  - livenessProbe: `/actuator/health/liveness` port 8087, initialDelay 35s, period 10s, timeout 5s
  - readinessProbe: `/actuator/health/readiness` port 8087, initialDelay 20s, period 5s, timeout 3s
- [x] **File**: `infra/k8s/services/notification-service/service.yml` — ClusterIP, port 8087
- [x] **File**: `infra/k8s/services/notification-service/hpa.yml` — name: `notification-service`, min 2, max 5, cpu 70%

---

### Part K: Tests and Verification (AC1–AC3)

#### Task 13: Run Regression Tests and Verify Health Probe Config

- [x] `cd backend && ./mvnw clean compile -T 1C` — ensure all 7 services compile after `application.yml` changes
- [x] `cd backend && ./mvnw test -pl :product-service` — verify no regressions (target: 0 failures) — 63/63 passed
- [x] `cd backend && ./mvnw test -pl :cart-service` — verify no regressions — 44/44 unit tests passed (integration tests skipped: Docker not running on dev machine)
- [x] `cd backend && ./mvnw test -pl :order-service` — verify Saga tests still pass (target: 0 failures) — 90/90 passed
- [x] `cd backend && ./mvnw test -pl :notification-service` — verify no regressions — 37/37 passed
- [x] Verify K8s YAML structure: `find infra/k8s -name "*.yml" | wc -l` → 24 files ✅
- [x] Spot-check each generated YAML file is valid structure (consistent indentation, no missing `apiVersion`, `kind`, `metadata.name`, `metadata.namespace` fields)

---

## Dev Notes

### Health Probe Mechanism — Spring Boot 4

When `management.health.probes.enabled: true` is set:
- Spring Boot registers `LivenessStateHealthIndicator` and `ReadinessStateHealthIndicator` beans
- `/actuator/health/liveness` → only checks `livenessState` (process is alive)
- `/actuator/health/readiness` → checks `readinessState` + any indicators included in `readiness` group
- K8s only looks at the HTTP response code: 200 → UP/healthy, 503 → DOWN/unhealthy
- Liveness failures cause pod restart; readiness failures remove pod from Service load balancer

**Health indicator bean names** (Spring Boot auto-configures these when the dependency is on the classpath):
- `db` → `DataSourceHealthIndicator` (spring-boot-starter-data-jpa or spring-boot-starter-jdbc)
- `redis` → `RedisHealthIndicator` (spring-boot-starter-data-redis)
- `elasticsearch` → `ElasticsearchHealthIndicator` (spring-boot-starter-data-elasticsearch)
- `kafka` → `KafkaHealthIndicator` (spring-kafka + Kafka auto-configuration)
- `livenessState` → `LivenessStateHealthIndicator` (always present)
- `readinessState` → `ReadinessStateHealthIndicator` (always present)

If an indicator is in the readiness group but the dependency isn't on the classpath, it's silently ignored — no error.

### YAML Merging Pattern — DO NOT Duplicate Management Block

Services already have a `management:` block in the base section. When adding probes/percentiles, **merge** into the existing block rather than creating a duplicate:

**WRONG (creates duplicate key, Spring Boot uses last value):**
```yaml
management:
  tracing:
    sampling:
      probability: ${TRACING_SAMPLE_RATE:1.0}
  ...

management:               # ← DUPLICATE KEY — do not do this
  health:
    probes:
      enabled: true
```

**CORRECT (merge into existing block):**
```yaml
management:
  tracing:
    sampling:
      probability: ${TRACING_SAMPLE_RATE:1.0}
  otlp:
    tracing:
      endpoint: http://localhost:4318/v1/traces
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  health:                          # ← added here
    probes:
      enabled: true
  endpoint:                        # ← added here (NOTE: singular, not plural)
    health:
      show-details: always
      group:
        liveness:
          include: livenessState
        readiness:
          include: readinessState,db,kafka
  metrics:
    distribution:
      percentiles-histogram:
        "[http.server.requests]": true
      percentiles:
        "[http.server.requests]": 0.5,0.95,0.99
```

### Profile vs. Base Config — Why This Matters

Services have `spring.profiles.active: dev` in their base config. This means the `dev` profile block is always merged on top of the base. Some dev profile settings that conflict:

- **product-service dev**: `management.health.elasticsearch.enabled: false` → Elasticsearch health won't appear in readiness in dev. This is correct behavior (no ES in local dev).
- **dev profile actuator exposure**: Some services reduce to `health,info,metrics` (no prometheus) in dev profile. The K8s ConfigMap overrides this with `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,metrics,prometheus`.

The `management.health.probes.enabled: true` in the BASE section is NOT overridden by dev profiles (no dev profile sets it to false), so probes will be active in all environments.

### K8s Configuration Override Strategy (No Config Server)

The architecture decision (confirmed): **No Spring Cloud Config Server**. Override strategy:

```
application.yml base config        ← default values (localhost:*)
    ↓ merged with
application.yml profile block      ← dev-specific overrides
    ↓ overridden by
K8s ConfigMap env vars             ← K8s-specific shared config
    ↓ overridden by
K8s Secret env vars                ← K8s-specific sensitive config (DB passwords)
```

Spring Boot relaxed binding: env vars override properties regardless of active profile.
Example: `SPRING_DATASOURCE_URL` always overrides `spring.datasource.url` even if set in dev profile.

### K8s Service Naming Convention

K8s service name = Spring `spring.application.name`. Within the `robomart` namespace, services resolve to:
- `product-service.robomart.svc.cluster.local:8081` (full DNS)
- `product-service:8081` (short form within same namespace)

The ConfigMap uses short-form DNS (`http://product-service:8081`).

### gRPC Services in K8s

order-service (9093), inventory-service (9094), payment-service (9095) expose gRPC ports. The Service manifest must expose both HTTP and gRPC ports. The deployment must declare both containerPorts. gRPC health checks via K8s liveness/readiness use the HTTP actuator (not gRPC health protocol) — this is correct.

### K8s Manifest Image References

Use `ghcr.io/robomart/{service-name}:latest` as the image reference. The actual CI/CD pipeline (Story 10.4) will build and push images to GitHub Container Registry. These manifests are a prerequisite for that story.

In development/testing, replace `latest` with a specific tag:
```bash
kubectl set image deployment/product-service product-service=ghcr.io/robomart/product-service:v1.2.3 -n robomart
```

### Kafka Consumer Lag Metrics (AC3)

Already configured in Story 9.1 via `spring.kafka.listener.observation-enabled: true`. Spring Kafka 4.x Micrometer integration auto-exposes:
- `kafka.consumer.fetch-latency-avg` — consumer poll latency
- `spring.kafka.listener` observations → histogram in Prometheus

For dedicated consumer lag (messages behind):
- `kafka.consumer.records-lag` metric is provided by Spring Kafka's `KafkaMetrics` listener
- Auto-configured when `spring-kafka` is on classpath and consumer groups are active
- No additional config needed beyond what Story 9.1 already set

### Spring Boot 4 Testing Patterns (continued from Stories 8.x, 9.1)

- `@MockitoBean` from `org.springframework.test.context.bean.override.mockito` (NOT deprecated `@MockBean`)
- For service tests that check `application.yml` loading: use `@SpringBootTest(properties = {...})` to override specific properties
- Health endpoint tests (integration): use `RestClient` + `@LocalServerPort` pattern

### Existing Implementation to NOT Change

Already complete — do not touch:
- `HealthAggregatorService.java` (notification-service) — cross-service health dashboard; separate from K8s probes
- `AdminSystemHealthRestController.java` (notification-service) — admin UI endpoint for system health
- `logback-spring.xml` — already includes `correlationId` and `traceId` in JSON output
- Any existing `HealthIndicator` references in other services

### Checkstyle

No Java code changes in this story — all changes are YAML/K8s manifests. Checkstyle does not apply. Still run `./mvnw clean compile -T 1C` to verify no regressions from YAML config changes.

---

### Project Structure Notes

**New files (24 total):**
- `infra/k8s/base/namespace.yml`
- `infra/k8s/base/configmap.yml`
- `infra/k8s/base/secrets-template.yml`
- `infra/k8s/services/api-gateway/deployment.yml`
- `infra/k8s/services/api-gateway/service.yml`
- `infra/k8s/services/api-gateway/hpa.yml`
- `infra/k8s/services/product-service/deployment.yml`
- `infra/k8s/services/product-service/service.yml`
- `infra/k8s/services/product-service/hpa.yml`
- `infra/k8s/services/cart-service/deployment.yml`
- `infra/k8s/services/cart-service/service.yml`
- `infra/k8s/services/cart-service/hpa.yml`
- `infra/k8s/services/order-service/deployment.yml`
- `infra/k8s/services/order-service/service.yml`
- `infra/k8s/services/order-service/hpa.yml`
- `infra/k8s/services/inventory-service/deployment.yml`
- `infra/k8s/services/inventory-service/service.yml`
- `infra/k8s/services/inventory-service/hpa.yml`
- `infra/k8s/services/payment-service/deployment.yml`
- `infra/k8s/services/payment-service/service.yml`
- `infra/k8s/services/payment-service/hpa.yml`
- `infra/k8s/services/notification-service/deployment.yml`
- `infra/k8s/services/notification-service/service.yml`
- `infra/k8s/services/notification-service/hpa.yml`

**Modified files:**
- `backend/api-gateway/src/main/resources/application.yml` — add probes, update readiness group (redis), change show-details to always, add percentile histogram to base
- `backend/product-service/src/main/resources/application.yml` — add probes, readiness group (db,redis,elasticsearch,kafka), move percentile histogram from dev profile to base
- `backend/cart-service/src/main/resources/application.yml` — add probes, readiness group (redis,kafka), add percentile histogram to base
- `backend/order-service/src/main/resources/application.yml` — add probes, readiness group (db,kafka), add percentile histogram to base
- `backend/inventory-service/src/main/resources/application.yml` — add probes, readiness group (db,redis,kafka), add percentile histogram to base
- `backend/payment-service/src/main/resources/application.yml` — add probes, readiness group (db,kafka), add percentile histogram to base
- `backend/notification-service/src/main/resources/application.yml` — add probes, readiness group (db,kafka), add percentile histogram to base

---

### References

- Story 9.2 requirements: `_bmad-output/planning-artifacts/epics.md` (Epic 9, Story 9.2)
- Architecture — K8s manifests structure: `_bmad-output/planning-artifacts/architecture.md` (lines 1673–1688)
- Architecture — Resource limits (NFR25): `_bmad-output/planning-artifacts/architecture.md` (line 44)
- Architecture — Configuration decision (no Config Server): `_bmad-output/planning-artifacts/architecture.md` (lines 1027–1031)
- Architecture — Observability stack: `_bmad-output/planning-artifacts/architecture.md` (lines 454–497)
- Story 9.1 dev notes (OpenTelemetry + Kafka observation): `_bmad-output/implementation-artifacts/9-1-implement-distributed-tracing-correlation-id-propagation.md`
- Current api-gateway config: `backend/api-gateway/src/main/resources/application.yml` (has Redis rate limiting, show-details: when-authorized)
- Current product-service config: `backend/product-service/src/main/resources/application.yml` (percentiles in dev profile only)
- Current notification-service config: `backend/notification-service/src/main/resources/application.yml` (uses env vars for downstream service URLs — good K8s pattern)
- K8s directory (currently empty): `infra/k8s/` (only `.gitkeep` present)

## Dev Agent Record

### Agent Model Used

_claude-sonnet-4-6_

### Debug Log References

Pre-existing compilation error in `api-gateway/RateLimitingFilter.java` — `RateLimiter.Response.getHeadersToAdd()` was removed in Spring Cloud 2025.1.1. Fixed by replacing with `response.getHeaders().forEach((k, v) -> ...)` since `getHeaders()` now returns `Map<String, String>` instead of `HttpHeaders`.

### Completion Notes List

- All 7 service `application.yml` files updated with `management.health.probes.enabled: true` and per-service readiness group config merged into existing BASE management block (not duplicated)
- api-gateway `show-details` changed from `when-authorized` → `always` for K8s probe compatibility
- All 7 services now expose p50/p95/p99 percentile metrics with `percentiles-histogram: true` in BASE section
- 24 K8s manifests created: 3 base (namespace, configmap, secrets-template) + 21 service manifests (deployment/service/hpa per service)
- order-service, inventory-service, payment-service deployments expose both HTTP and gRPC ports
- api-gateway Service type: LoadBalancer; all others: ClusterIP
- notification-db-secret includes both `SPRING_DATASOURCE_*` and `DB_USER`/`DB_PASSWORD` keys
- Fixed pre-existing `RateLimitingFilter.java` compile error (getHeadersToAdd → getHeaders + forEach)
- All tests pass: product-service 63/63, order-service 90/90, notification-service 37/37, cart-service unit 44/44

### File List

**Modified:**
- `backend/api-gateway/src/main/resources/application.yml`
- `backend/api-gateway/src/main/java/com/robomart/gateway/filter/RateLimitingFilter.java`
- `backend/product-service/src/main/resources/application.yml`
- `backend/cart-service/src/main/resources/application.yml`
- `backend/order-service/src/main/resources/application.yml`
- `backend/inventory-service/src/main/resources/application.yml`
- `backend/payment-service/src/main/resources/application.yml`
- `backend/notification-service/src/main/resources/application.yml`

**Created:**
- `infra/k8s/base/namespace.yml`
- `infra/k8s/base/configmap.yml`
- `infra/k8s/base/secrets-template.yml`
- `infra/k8s/services/api-gateway/deployment.yml`
- `infra/k8s/services/api-gateway/service.yml`
- `infra/k8s/services/api-gateway/hpa.yml`
- `infra/k8s/services/product-service/deployment.yml`
- `infra/k8s/services/product-service/service.yml`
- `infra/k8s/services/product-service/hpa.yml`
- `infra/k8s/services/cart-service/deployment.yml`
- `infra/k8s/services/cart-service/service.yml`
- `infra/k8s/services/cart-service/hpa.yml`
- `infra/k8s/services/order-service/deployment.yml`
- `infra/k8s/services/order-service/service.yml`
- `infra/k8s/services/order-service/hpa.yml`
- `infra/k8s/services/inventory-service/deployment.yml`
- `infra/k8s/services/inventory-service/service.yml`
- `infra/k8s/services/inventory-service/hpa.yml`
- `infra/k8s/services/payment-service/deployment.yml`
- `infra/k8s/services/payment-service/service.yml`
- `infra/k8s/services/payment-service/hpa.yml`
- `infra/k8s/services/notification-service/deployment.yml`
- `infra/k8s/services/notification-service/service.yml`
- `infra/k8s/services/notification-service/hpa.yml`

---

### Review Findings

#### Decision-Needed

- [x] [Review][Decision] **D1: api-gateway `show-details: always` exposes health topology on public port** — K8s liveness/readiness probes only need the HTTP status code (200/503), NOT response body details. Changing from `when-authorized` to `always` was not strictly required for probes to work. With the api-gateway running on a `LoadBalancer` Service (public IP), full health details (dependencies, DB state, Redis connectivity) are readable by anyone with network access. Options: (a) revert api-gateway to `show-details: when-authorized`; (b) move actuator to a separate management port (`management.server.port: 8090`) not exposed by LoadBalancer; (c) accept risk (cluster RBAC / firewall handles it).

- [x] [Review][Decision] **D2: order-service gRPC client addresses hardcoded to `localhost` — no K8s override** — `backend/order-service/src/main/resources/application.yml:30-33` sets `static://localhost:9094` (inventory) and `localhost:9095` (payment) in the BASE section. In K8s, `localhost` resolves to the pod's own loopback — all Saga gRPC calls (ReserveInventory, ProcessPayment) will fail with connection refused. The ConfigMap has no gRPC client address entries. Proposed fix: change base config to use `${...}` interpolation (`'${GRPC_CLIENT_INVENTORY_SERVICE_ADDRESS:static://localhost:9094}'`) and add corresponding entries to `infra/k8s/base/configmap.yml`. Confirm approach or prefer env vars directly in the deployment manifest?

#### Patches

- [x] [Review][Patch] **P1: inventory-service and payment-service deployments missing `GRPC_SERVER_PORT` env var** [`infra/k8s/services/inventory-service/deployment.yml`, `infra/k8s/services/payment-service/deployment.yml`] — inconsistent with `order-service/deployment.yml` which explicitly sets `GRPC_SERVER_PORT: "9093"`. Add `env: - name: GRPC_SERVER_PORT value: "9094"` (inventory) and `"9095"` (payment).

#### Deferred

- [x] [Review][Defer] **W1: Dev profiles strip `prometheus` from actuator exposure** [`backend/*/src/main/resources/application.yml`] — deferred, pre-existing. Mitigated in K8s by ConfigMap `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE`. Affects local dev only.

- [x] [Review][Defer] **W2: Memory limits 512Mi potentially tight for heavy-dependency services** [`infra/k8s/services/*/deployment.yml`] — deferred, pre-existing sizing decision.

- [x] [Review][Defer] **W3: HPA CPU-only scaling — no memory metric for JVM workloads** [`infra/k8s/services/*/hpa.yml`] — deferred, enhancement beyond story scope.

- [x] [Review][Defer] **W4: No TLS on api-gateway LoadBalancer** [`infra/k8s/services/api-gateway/service.yml`] — deferred, Story 10.4 / infra scope.

- [x] [Review][Defer] **W5: No `imagePullSecrets` for ghcr.io private registry** [`infra/k8s/services/*/deployment.yml`] — deferred, infra setup concern.

- [x] [Review][Defer] **W6: Readiness probe `timeoutSeconds: 3` tight for DB health under load** [`infra/k8s/services/*/deployment.yml`] — deferred, requires load testing to confirm.

- [x] [Review][Defer] **W7: RateLimitingFilter fail-open on Redis error** [`backend/api-gateway/.../RateLimitingFilter.java`] — deferred, pre-existing Story 8.3 behavior.
