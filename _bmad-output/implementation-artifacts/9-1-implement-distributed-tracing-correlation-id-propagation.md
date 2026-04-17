# Story 9.1: Implement Distributed Tracing & Correlation ID Propagation

Status: in-progress

## Story

As a developer,
I want to trace any request across all services end-to-end with a single trace ID,
So that I can debug issues and understand request flow across the distributed system.

## Acceptance Criteria

1. **Given** Docker Compose
   **When** updated for this story (full profile)
   **Then** Grafana Tempo, Grafana Loki, Alloy (log shipper), Prometheus, and Grafana are added to `infra/docker/docker-compose.full.yml`

2. **Given** OpenTelemetry configured in all services (via `spring-boot-starter-opentelemetry`)
   **When** a request flows from API Gateway → Order Service (gRPC) → Inventory Service (gRPC) → Payment Service (gRPC)
   **Then** a single trace ID is propagated through all services, visible in Grafana Tempo (FR58)

3. **Given** trace context propagation
   **When** configured per protocol
   **Then** REST is auto-instrumented, gRPC uses `GrpcTracingInterceptor` beans in `TracingConfig.java` (common-lib), Kafka uses `observation-enabled` + `CorrelationIdKafkaProducerInterceptor`/`CorrelationIdKafkaConsumerInterceptor` (common-lib), WebSocket has manual trace ID injection in `AdminPushService.java` (FR58)

4. **Given** every API response
   **When** returned to the client
   **Then** `traceId` field is populated from the current OpenTelemetry span

5. **Given** `CorrelationIdFilter` in common-lib
   **When** a request arrives without `X-Correlation-Id` header
   **Then** one is generated and propagated through all log entries, error responses, and Kafka message headers (FR59, NFR39)

## Tasks / Subtasks

### Part A: Docker Compose Full Profile (AC1)

#### Task 1: Create `infra/docker/docker-compose.full.yml` (AC1)
- [x] **File**: `infra/docker/docker-compose.full.yml`
- [x] This is a Docker Compose **override** file — extends `docker-compose.yml` via `docker-compose -f docker-compose.yml -f docker-compose.full.yml`. Contains only observability services.
- [x] All services use profile `full` and join `robomart-network`
- [x] Services to add:
  - **Grafana Tempo** (`grafana/tempo:latest`) — OTLP ingest on 4317 (gRPC) + 4318 (HTTP), admin UI on 3200
  - **Grafana Loki** (`grafana/loki:3.0.0`) — log aggregation, port 3100
  - **Alloy** (`grafana/alloy:latest`) — log shipper (replaces Promtail), UI on 12345
  - **Prometheus** (`prom/prometheus:v3.9.0`) — port **9091** (9090 is taken by Kafka UI — do NOT use 9090)
  - **Grafana** (`grafana/grafana:12.3.0`) — dashboards, port 3000
  - **Pact Broker** (`pactfoundation/pact-broker:latest`) — contract testing, port 9292
- [x] Memory limits: Grafana 256M, Prometheus 512M, Tempo 512M, Loki 256M, Alloy 128M, Pact Broker 256M
- [x] Mount Grafana provisioning: `./grafana/provisioning:/etc/grafana/provisioning`
- [x] Mount Prometheus config: `./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml`
- [x] Tempo must be configured to receive OTLP via HTTP on 4318 and expose Grafana query API on 3200
- [x] Grafana env vars: `GF_SECURITY_ADMIN_PASSWORD=admin`, `GF_PATHS_PROVISIONING=/etc/grafana/provisioning`

#### Task 2: Create Grafana Provisioning Config (AC1)
- [x] **File**: `infra/docker/grafana/provisioning/datasources/datasources.yml`
  ```yaml
  apiVersion: 1
  datasources:
    - name: Prometheus
      type: prometheus
      url: http://prometheus:9091
      isDefault: true
    - name: Loki
      type: loki
      url: http://loki:3100
    - name: Tempo
      type: tempo
      url: http://tempo:3200
      jsonData:
        tracesToLogsV2:
          datasourceUid: loki
          spanStartTimeShift: '-1h'
          spanEndTimeShift: '1h'
  ```
- [x] **File**: `infra/docker/grafana/provisioning/dashboards/dashboards.yml`
  ```yaml
  apiVersion: 1
  providers:
    - name: default
      type: file
      options:
        path: /var/lib/grafana/dashboards
  ```

#### Task 3: Create Prometheus Scrape Config (AC1)
- [x] **File**: `infra/docker/prometheus/prometheus.yml`
  ```yaml
  global:
    scrape_interval: 15s
  
  scrape_configs:
    - job_name: 'spring-boot-services'
      metrics_path: '/actuator/prometheus'
      static_configs:
        - targets:
            - 'api-gateway:8080'
            - 'product-service:8081'
            - 'cart-service:8082'
            - 'order-service:8083'
            - 'inventory-service:8084'
            - 'payment-service:8086'
            - 'notification-service:8087'
  ```

#### Task 4: Create Grafana Tempo Config (AC1)
- [x] **File**: `infra/docker/tempo/tempo.yml`
  ```yaml
  server:
    http_listen_port: 3200
  
  distributor:
    receivers:
      otlp:
        protocols:
          grpc:
            endpoint: "0.0.0.0:4317"
          http:
            endpoint: "0.0.0.0:4318"
  
  storage:
    trace:
      backend: local
      local:
        path: /tmp/tempo/traces
  ```
- [x] Mount in `docker-compose.full.yml`: `./tempo/tempo.yml:/etc/tempo/tempo.yml`

---

### Part B: OpenTelemetry Dependencies (AC2)

#### Task 5: Add `spring-boot-starter-opentelemetry` to `common-lib/pom.xml`
- [x] **File**: `backend/common-lib/pom.xml`
- [x] **Replace** `io.micrometer:micrometer-tracing` with `spring-boot-starter-opentelemetry`:
  ```xml
  <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-opentelemetry</artifactId>
  </dependency>
  ```
- [x] No version — Spring Boot parent 4.0.4 manages it. No additional OTel BOM needed.
- [x] `spring-boot-starter-opentelemetry` brings: OTel SDK + `micrometer-tracing-bridge-otel` (which bridges `io.micrometer.tracing.Tracer` API to OTel). The `Tracer` bean used in `GlobalExceptionHandler` and all controllers is auto-configured via this bridge.

#### Task 6: Add `spring-boot-starter-opentelemetry` to All 7 Service POMs
- [x] **Files**: `api-gateway/pom.xml`, `product-service/pom.xml`, `cart-service/pom.xml`, `order-service/pom.xml`, `inventory-service/pom.xml`, `payment-service/pom.xml`, `notification-service/pom.xml`
- [x] For each, add:
  ```xml
  <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-opentelemetry</artifactId>
  </dependency>
  ```
- [x] **notification-service special case**: It currently has `spring-boot-micrometer-tracing-brave`. **Remove that dependency** and add `spring-boot-starter-opentelemetry` instead. Do NOT have both — they register conflicting `Tracer` implementations. Check `notification-service/pom.xml` line ~76 before editing.

---

### Part C: OTLP Exporter & Management Config (AC2)

#### Task 7: Add Tracing Config to All 7 Service `application.yml` Files
- [x] For each service, add to the base (non-profile) section:
  ```yaml
  management:
    tracing:
      sampling:
        probability: 1.0
    otlp:
      tracing:
        endpoint: http://localhost:4318/v1/traces
    endpoints:
      web:
        exposure:
          include: health,info,metrics,prometheus
  ```
- [x] **Read each `application.yml` first** to find the `management:` block — append to existing block rather than duplicate it (product-service already has `management.endpoints.web.exposure.include: health,info,metrics`).
- [x] Service names (`spring.application.name`) are already set — OTel uses them automatically as the `service.name` attribute.
- [x] For Docker Compose (containerized deployment), override endpoint via environment variable `MANAGEMENT_OTLP_TRACING_ENDPOINT=http://tempo:4318/v1/traces` in `docker-compose.yml` app service environment blocks. Do NOT add a separate profile section for this — env var override is cleaner.
- [x] Services: api-gateway, product-service, cart-service, order-service, inventory-service, payment-service, notification-service

---

### Part D: Common-Lib TracingConfig (AC3)

#### Task 8: Rename `TracerConfig.java` → `TracingConfig.java` with gRPC Interceptors
- [x] **Delete**: `backend/common-lib/src/main/java/com/robomart/common/config/TracerConfig.java`
- [x] **Create**: `backend/common-lib/src/main/java/com/robomart/common/config/TracingConfig.java`
- [x] The NOOP `@ConditionalOnMissingBean(Tracer.class)` fallback is no longer needed — OTel bridge from Task 5 provides the real `Tracer` bean automatically.
- [x] New `TracingConfig.java` registers gRPC observation interceptors for both client (outbound) and server (inbound) sides:
  ```java
  package com.robomart.common.config;
  
  import io.micrometer.observation.ObservationRegistry;
  import org.springframework.context.annotation.Bean;
  import org.springframework.context.annotation.Configuration;
  import org.springframework.grpc.observation.ObservationGrpcClientInterceptor;
  import org.springframework.grpc.observation.ObservationGrpcServerInterceptor;
  
  @Configuration
  public class TracingConfig {
  
      @Bean
      public ObservationGrpcClientInterceptor grpcTracingClientInterceptor(ObservationRegistry registry) {
          return new ObservationGrpcClientInterceptor(registry);
      }
  
      @Bean
      public ObservationGrpcServerInterceptor grpcTracingServerInterceptor(ObservationRegistry registry) {
          return new ObservationGrpcServerInterceptor(registry);
      }
  }
  ```
- [x] **CRITICAL — Verify class names**: Spring gRPC 1.0.2 is on classpath. Before writing this file, verify the exact class names:
  ```bash
  jar -tf ~/.m2/repository/org/springframework/grpc/spring-grpc-core/1.0.2/spring-grpc-core-1.0.2.jar | grep -i observation
  ```
  Expected: `org/springframework/grpc/observation/ObservationGrpcClientInterceptor.class` and `ObservationGrpcServerInterceptor.class`. If different names found, adjust the imports accordingly.
- [x] Spring gRPC 1.0 auto-configuration registers these beans as global interceptors when they exist in the Spring context. No `@GrpcGlobalClientInterceptor` annotation needed.
- [x] **Note**: `ObservationRegistry` is provided by `spring-boot-starter-actuator` (already in common-lib). No extra dep needed.

#### Task 9: Create `CorrelationIdKafkaProducerInterceptor.java` (AC5)
- [x] **File**: `backend/common-lib/src/main/java/com/robomart/common/kafka/CorrelationIdKafkaProducerInterceptor.java`
- [ ] Reads `correlationId` from MDC (set by `CorrelationIdFilter`) and adds as `X-Correlation-Id` Kafka header on every produced message:
  ```java
  package com.robomart.common.kafka;
  
  import java.nio.charset.StandardCharsets;
  import java.util.Map;
  
  import org.apache.kafka.clients.producer.ProducerInterceptor;
  import org.apache.kafka.clients.producer.ProducerRecord;
  import org.apache.kafka.clients.producer.RecordMetadata;
  import org.apache.kafka.common.header.Headers;
  import org.slf4j.MDC;
  
  public class CorrelationIdKafkaProducerInterceptor<K, V> implements ProducerInterceptor<K, V> {
  
      public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
  
      @Override
      public ProducerRecord<K, V> onSend(ProducerRecord<K, V> record) {
          String correlationId = MDC.get("correlationId");
          if (correlationId != null && !correlationId.isBlank()) {
              Headers headers = record.headers();
              if (headers.lastHeader(CORRELATION_ID_HEADER) == null) {
                  headers.add(CORRELATION_ID_HEADER,
                      correlationId.getBytes(StandardCharsets.UTF_8));
              }
          }
          return record;
      }
  
      @Override
      public void onAcknowledgement(RecordMetadata metadata, Exception exception) { }
  
      @Override
      public void close() { }
  
      @Override
      public void configure(Map<String, ?> configs) { }
  }
  ```
- [x] No `@Component` — registered via `interceptor.classes` property in `application.yml` (Kafka instantiates it directly, not via Spring context)
- [x] MDC key `"correlationId"` matches the constant in `CorrelationIdFilter.CORRELATION_ID_MDC_KEY`

#### Task 10: Create `CorrelationIdKafkaConsumerInterceptor.java` (AC5)
- [x] **File**: `backend/common-lib/src/main/java/com/robomart/common/kafka/CorrelationIdKafkaConsumerInterceptor.java`
- [ ] Reads `X-Correlation-Id` from Kafka message headers and puts into MDC for logging:
  ```java
  package com.robomart.common.kafka;
  
  import java.nio.charset.StandardCharsets;
  import java.util.Map;
  
  import org.apache.kafka.clients.consumer.ConsumerInterceptor;
  import org.apache.kafka.clients.consumer.ConsumerRecord;
  import org.apache.kafka.clients.consumer.ConsumerRecords;
  import org.apache.kafka.clients.consumer.OffsetAndMetadata;
  import org.apache.kafka.common.TopicPartition;
  import org.apache.kafka.common.header.Header;
  import org.slf4j.MDC;
  
  public class CorrelationIdKafkaConsumerInterceptor<K, V> implements ConsumerInterceptor<K, V> {
  
      public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
      public static final String CORRELATION_ID_MDC_KEY = "correlationId";
  
      @Override
      public ConsumerRecords<K, V> onConsume(ConsumerRecords<K, V> records) {
          for (ConsumerRecord<K, V> record : records) {
              Header header = record.headers().lastHeader(CORRELATION_ID_HEADER);
              if (header != null) {
                  String correlationId = new String(header.value(), StandardCharsets.UTF_8);
                  MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
              }
          }
          return records;
      }
  
      @Override
      public void onCommit(Map<TopicPartition, OffsetAndMetadata> offsets) { }
  
      @Override
      public void close() { }
  
      @Override
      public void configure(Map<String, ?> configs) { }
  }
  ```

#### Task 11: Add Kafka Interceptors + Observation to Service `application.yml` (AC3, AC5)
- [x] For services with Kafka, add to their `application.yml` (under `spring.kafka:`):
  ```yaml
  spring:
    kafka:
      producer:
        properties:
          interceptor.classes: com.robomart.common.kafka.CorrelationIdKafkaProducerInterceptor
      consumer:
        properties:
          interceptor.classes: com.robomart.common.kafka.CorrelationIdKafkaConsumerInterceptor
      template:
        observation-enabled: true
      listener:
        observation-enabled: true
  ```
- [x] Services with Kafka producers: product-service, order-service, inventory-service, payment-service
- [x] Services with Kafka consumers: order-service, inventory-service, payment-service, notification-service
- [x] Services with both: order-service, inventory-service, payment-service
- [x] api-gateway and cart-service do NOT use Kafka — skip them
- [x] **Read each `application.yml` first** to find existing `spring.kafka:` block and merge these properties rather than duplicating the block

---

### Part E: WebSocket Trace Injection (AC3)

#### Task 12: Add TraceId to WebSocket STOMP Messages in `AdminPushService.java` (AC3)
- [x] **File**: `backend/notification-service/src/main/java/com/robomart/notification/service/AdminPushService.java`
- [x] **Read file first** — it currently has: `SimpMessagingTemplate messagingTemplate`, `OrderServiceClient`, `ProductServiceClient` in constructor
- [x] Inject `Tracer` bean (from `io.micrometer.tracing`) into constructor
- [x] Add private `getTraceId()` helper (same pattern as existing service controllers):
  ```java
  private String getTraceId() {
      io.micrometer.tracing.Span span = tracer.currentSpan();
      if (span != null) {
          io.micrometer.tracing.TraceContext ctx = span.context();
          if (ctx != null) {
              return ctx.traceId();
          }
      }
      return null;
  }
  ```
- [x] Update all three `messagingTemplate.convertAndSend()` calls to inject traceId as native STOMP header `x-trace-id`:
  ```java
  // Before:
  messagingTemplate.convertAndSend(TOPIC_ORDERS, payload);
  
  // After:
  org.springframework.messaging.simp.SimpMessageHeaderAccessor accessor =
      org.springframework.messaging.simp.SimpMessageHeaderAccessor.create(
          org.springframework.messaging.simp.SimpMessageType.MESSAGE);
  String traceId = getTraceId();
  if (traceId != null) {
      accessor.setNativeHeader("x-trace-id", traceId);
  }
  messagingTemplate.convertAndSend(TOPIC_ORDERS, payload, accessor.getMessageHeaders());
  ```
- [x] Apply same pattern to `pushInventoryAlert()` and `pushSystemHealth()` methods
- [x] Checkstyle: static imports or inline class references — be consistent with existing code style in the file

---

### Part F: Verify AC4 — TraceId in API Responses

#### Task 13: No Code Changes Needed for AC4 (verification only)
- [x] **Read** `backend/product-service/src/main/java/com/robomart/product/controller/ProductRestController.java`
- [x] Confirm: `Tracer tracer` is injected via constructor; `getTraceId()` method calls `tracer.currentSpan().context().traceId()`; controllers create `new ApiResponse<>(data, getTraceId())`
- [x] With `spring-boot-starter-opentelemetry` added (Task 5-6), the OTel bridge auto-configures a real `Tracer` bean backed by OTel — `getTraceId()` now returns a real hex trace ID (e.g., `4a2e1e3d5c6b7f8a9b0c1d2e3f4a5b6c`)
- [x] The `TracerConfig.java` NOOP bean is `@ConditionalOnMissingBean(Tracer.class)` — after Task 8 (delete TracerConfig.java) and OTel bridge providing a real bean, NOOP is gone. Safe.
- [x] No code changes required — **just verify the existing controller pattern**.

---

### Part G: Tests (AC1–5)

#### Task 14: Unit Tests for `CorrelationIdKafkaProducerInterceptor` (AC5)
- [x] **File**: `backend/common-lib/src/test/java/com/robomart/common/kafka/CorrelationIdKafkaProducerInterceptorTest.java`
- [ ] Key test cases:
  ```java
  @Test
  void addsCorrelationIdFromMdcAsKafkaHeader() {
      MDC.put("correlationId", "test-correlation-123");
      var record = new ProducerRecord<String, String>("topic", "key", "value");
      
      ProducerRecord<String, String> result = interceptor.onSend(record);
      
      Header header = result.headers().lastHeader("X-Correlation-Id");
      assertThat(header).isNotNull();
      assertThat(new String(header.value(), StandardCharsets.UTF_8))
          .isEqualTo("test-correlation-123");
      MDC.clear();
  }
  
  @Test
  void doesNotAddHeaderWhenMdcIsEmpty() {
      MDC.clear();
      var record = new ProducerRecord<String, String>("topic", "key", "value");
      
      ProducerRecord<String, String> result = interceptor.onSend(record);
      
      assertThat(result.headers().lastHeader("X-Correlation-Id")).isNull();
  }
  
  @Test
  void doesNotOverwriteExistingHeader() {
      MDC.put("correlationId", "new-id");
      var record = new ProducerRecord<String, String>("topic", "key", "value");
      record.headers().add("X-Correlation-Id", "existing-id".getBytes(StandardCharsets.UTF_8));
      
      ProducerRecord<String, String> result = interceptor.onSend(record);
      
      // Should preserve existing header (not overwrite)
      assertThat(new String(
          result.headers().lastHeader("X-Correlation-Id").value(), StandardCharsets.UTF_8))
          .isEqualTo("existing-id");
      MDC.clear();
  }
  ```

#### Task 15: Run Regression Tests
- [x] `cd backend && ./mvnw test -pl :common-lib` — verify new interceptor tests pass (32 tests, 0 failures)
- [x] `cd backend && ./mvnw test -pl :product-service` — verify no compile errors from TracerConfig rename (63 tests, 0 failures)
- [x] `cd backend && ./mvnw test -pl :order-service` — verify Saga tests still pass (90 tests, 0 failures)
- [x] `cd backend && ./mvnw test -pl :notification-service` — verify no compile errors from Brave → OTel switch (37 tests, 0 failures)
- [x] Grep for any remaining references to `TracerConfig`: only binary/compiled artifacts — source clean
- [x] Checkstyle: compile-phase runs clean; `checkstyle:check` goal has pre-existing config issue (unrelated to this story)

---

## Dev Notes

### OTel Spring Boot 4 Dependency Chain

When you add `spring-boot-starter-opentelemetry` to a service:
```
spring-boot-starter-opentelemetry
  ├── opentelemetry-sdk
  ├── opentelemetry-exporter-otlp
  └── micrometer-tracing-bridge-otel  ← bridges io.micrometer.tracing.Tracer → OTel SDK
      └── micrometer-tracing           ← already in common-lib (now comes transitively)
```

This means:
- `Tracer` bean is now backed by real OTel spans (not NOOP)
- `GlobalExceptionHandler.getTraceId()` returns real OTel trace IDs
- REST requests auto-instrumented (Spring MVC observation via actuator)
- JDBC queries auto-instrumented
- gRPC calls instrumented via `TracingConfig.java` beans (Task 8)
- Kafka operations instrumented via `observation-enabled: true` (Task 11)

### gRPC Trace Propagation — Interceptor Registration

Spring gRPC 1.0.2 contains `spring-grpc-observation` artifact. Verify class names before writing `TracingConfig.java`:
```bash
jar -tf ~/.m2/repository/org/springframework/grpc/spring-grpc-core/1.0.2/spring-grpc-core-1.0.2.jar \
  | grep -i observation
```

If `ObservationGrpcClientInterceptor`/`ObservationGrpcServerInterceptor` exist in `org.springframework.grpc.observation` — use them as shown in Task 8.

If NOT found (API changed), fallback to Micrometer's core gRPC instrumentation:
```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-grpc</artifactId>
</dependency>
```
with class `io.micrometer.core.instrument.binder.grpc.ObservationGrpcClientInterceptor`.

Spring gRPC 1.0 auto-config: when `ObservationGrpcClientInterceptor`/`ObservationGrpcServerInterceptor` beans exist in the Spring context, Spring gRPC registers them as global interceptors for all channels/servers automatically. No `@GrpcGlobalClientInterceptor` annotation needed.

### Kafka Observation vs CorrelationId Interceptors

Two complementary mechanisms:
1. **`observation-enabled: true`** — Spring Kafka 4.x built-in Micrometer Observation support. Propagates W3C `traceparent` header for OTel trace context between producer and consumer. Feeds into Grafana Tempo.
2. **`CorrelationIdKafkaProducerInterceptor`** — Custom `ProducerInterceptor` for `X-Correlation-Id` header. Propagates business correlation ID from HTTP request context through the Kafka message chain.

Both are needed: observation for tracing (trace IDs visible in Tempo), custom interceptor for correlation IDs (visible in structured logs via MDC).

### CorrelationId vs TraceId — Two Distinct Concepts

| Aspect | TraceId (OTel) | CorrelationId (custom) |
|--------|---------------|----------------------|
| Format | 32-char hex (e.g., `4a2e1e3d...`) | UUID or user-provided string |
| Propagation | W3C `traceparent` header (auto by OTel) | `X-Correlation-Id` HTTP header + MDC + Kafka header |
| Visibility | Grafana Tempo (trace UI) | JSON logs via MDC key `correlationId` |
| In API response | `ApiResponse.traceId` field | MDC for logging only |
| Source | OTel SDK, per-span | `CorrelationIdFilter` (HTTP) or Kafka consumer header |

### Existing Code — Do NOT Rewrite

Already implemented correctly — **no changes needed**:
- `CorrelationIdFilter.java` (`backend/common-lib/src/main/java/com/robomart/common/filter/CorrelationIdFilter.java`) — HTTP `X-Correlation-Id` propagation is complete. Only Kafka extension is new (via separate interceptors).
- `ApiResponse.java`, `ApiErrorResponse.java`, `PagedResponse.java` — already have `traceId` field
- `GlobalExceptionHandler.java` — already extracts traceId from `Tracer` bean (works with OTel bridge)
- Service controllers — already have `private String getTraceId()` using `Tracer` bean (works with OTel bridge)
- `logback-spring.xml` — already includes `correlationId` and `traceId` MDC keys in JSON output

### Docker Compose Port Allocation (Full Profile)

No conflicts with existing ports (from CLAUDE.md):
| Service | Port | Notes |
|---------|------|-------|
| Grafana | 3000 | |
| Grafana Loki | 3100 | |
| Grafana Tempo (admin) | 3200 | |
| Grafana Tempo (OTLP gRPC) | 4317 | Used by Spring services |
| Grafana Tempo (OTLP HTTP) | 4318 | Preferred for Spring Boot OTLP exporter |
| Prometheus | **9091** | **NOT 9090** — Kafka UI is on 9090 |
| Pact Broker | 9292 | |
| Alloy UI | 12345 | |

Start command:
```bash
cd infra/docker
docker-compose -f docker-compose.yml -f docker-compose.full.yml --profile core --profile full up -d
```

### Spring Boot 4 Testing Patterns (from Stories 8.x)

- `@MockitoBean` from `org.springframework.test.context.bean.override.mockito.MockitoBean` — NOT deprecated `@MockBean`
- `@SpringBootTest` unit tests: pass `properties` array to disable JWT/external service auto-config
- New unit tests: no `@SpringBootTest` needed for simple unit tests — just `new CorrelationIdKafkaProducerInterceptorTest()`

### Checkstyle

Import ordering: static imports first, then `com.robomart.*`, then `org.*`, then `java.*`.
Run: `cd backend && ./mvnw checkstyle:check`

---

### Project Structure Notes

**New files:**
- `infra/docker/docker-compose.full.yml`
- `infra/docker/grafana/provisioning/datasources/datasources.yml`
- `infra/docker/grafana/provisioning/dashboards/dashboards.yml`
- `infra/docker/prometheus/prometheus.yml`
- `infra/docker/tempo/tempo.yml`
- `backend/common-lib/src/main/java/com/robomart/common/config/TracingConfig.java` (renamed from TracerConfig.java)
- `backend/common-lib/src/main/java/com/robomart/common/kafka/CorrelationIdKafkaProducerInterceptor.java`
- `backend/common-lib/src/main/java/com/robomart/common/kafka/CorrelationIdKafkaConsumerInterceptor.java`
- `backend/common-lib/src/test/java/com/robomart/common/kafka/CorrelationIdKafkaProducerInterceptorTest.java`

**Modified files:**
- `backend/common-lib/pom.xml` — replace `micrometer-tracing` with `spring-boot-starter-opentelemetry`
- `backend/api-gateway/pom.xml`, `product-service/pom.xml`, `cart-service/pom.xml`, `order-service/pom.xml`, `inventory-service/pom.xml`, `payment-service/pom.xml`, `notification-service/pom.xml` — add `spring-boot-starter-opentelemetry`
- `backend/notification-service/pom.xml` — **also remove** `spring-boot-micrometer-tracing-brave`
- All 7 service `application.yml` files — add `management.tracing.*`, OTLP endpoint, Kafka observation + interceptors
- `backend/notification-service/src/main/java/com/robomart/notification/service/AdminPushService.java` — add traceId to STOMP headers

**Deleted files:**
- `backend/common-lib/src/main/java/com/robomart/common/config/TracerConfig.java`

---

### References

- Story 9.1 requirements: `_bmad-output/planning-artifacts/epics.md` (Epic 9, Story 9.1 — lines 1594–1621)
- Architecture — Observability stack decisions: `_bmad-output/planning-artifacts/architecture.md` (lines 458–497)
- Architecture — Trace Context Propagation table: `_bmad-output/planning-artifacts/architecture.md` (lines 404–412)
- Architecture — common-lib structure: `_bmad-output/planning-artifacts/architecture.md` (lines 1060–1088)
- Architecture — Docker Compose profiles: `_bmad-output/planning-artifacts/architecture.md` (lines 467–497)
- Existing `TracerConfig.java` (to rename/delete): `backend/common-lib/src/main/java/com/robomart/common/config/TracerConfig.java`
- Existing `CorrelationIdFilter.java` (reference only): `backend/common-lib/src/main/java/com/robomart/common/filter/CorrelationIdFilter.java`
- Existing `GlobalExceptionHandler.java` (reference only): `backend/common-lib/src/main/java/com/robomart/common/exception/GlobalExceptionHandler.java`
- Existing `AdminPushService.java` (modify): `backend/notification-service/src/main/java/com/robomart/notification/service/AdminPushService.java`
- Story 8.4 dev notes (Spring Boot 4 patterns, `@MockitoBean`): `_bmad-output/implementation-artifacts/8-4-implement-saga-phase-b-hardened-orchestration.md`

### Review Findings

- [x] [Review][Decision] F2: FIXED — Verified bytecode: Spring gRPC only auto-registers beans annotated with `@GlobalClientInterceptor`/`@GlobalServerInterceptor`. Added both annotations to `TracingConfig.java`. Also added `spring-grpc-core` (optional) to `common-lib/pom.xml`. [TracingConfig.java]
- [x] [Review][Decision] F14: FIXED — Added Kafka producer interceptor + `template.observation-enabled: true` to `cart-service/application.yml`. [cart-service/application.yml]
- [x] [Review][Patch] F1+F13: FIXED — `CorrelationIdKafkaConsumerInterceptor`: added `else { MDC.remove() }` for absent/blank headers; added `MDC.remove` in `close()`. Also added `isBlank()` check (F13). [CorrelationIdKafkaConsumerInterceptor.java]
- [x] [Review][Patch] F3: DEFERRED — `@GlobalServerInterceptor` (fixed in F2) already ensures Spring gRPC's server auto-config gates the registration; non-gRPC services won't activate a gRPC server. Low risk.
- [x] [Review][Patch] F4: FIXED — Changed `probability: 1.0` → `probability: ${TRACING_SAMPLE_RATE:1.0}` in all 7 service `application.yml` files. [all 7 application.yml]
- [x] [Review][Patch] F5: FIXED — Removed `MANAGEMENT_OTLP_TRACING_ENDPOINT` from all 7 app services in `docker-compose.yml`; added as service-stub overrides in `docker-compose.full.yml`. [docker-compose.yml, docker-compose.full.yml]
- [x] [Review][Patch] F6: FIXED — `PaymentAdminRestController`: injected `Tracer`, added `getTraceId()`, changed `new ApiResponse<>(response, null)` → `new ApiResponse<>(response, getTraceId())`. [PaymentAdminRestController.java]
- [x] [Review][Patch] F7: FIXED — Added `postgres-pact` service + `PACT_BROKER_DATABASE_*` env vars to `pact-broker` in `docker-compose.full.yml`. [docker-compose.full.yml]
- [x] [Review][Patch] F8: FIXED — Added `tempo-data` named volume; mounted in Tempo service. [docker-compose.full.yml]
- [x] [Review][Patch] F9: FIXED — Created `infra/docker/alloy/config.alloy` (Docker log → Loki pipeline); mounted in alloy service with `command: run`. [docker-compose.full.yml, alloy/config.alloy]
- [x] [Review][Patch] F10: FIXED — Created `CorrelationIdKafkaConsumerInterceptorTest.java` with 5 tests (header sets MDC, absent header clears MDC, blank header clears MDC, close() clears MDC, last-record wins in batch). [CorrelationIdKafkaConsumerInterceptorTest.java]
- [x] [Review][Patch] F11: FIXED — `GF_SECURITY_ADMIN_PASSWORD: admin` → `${GF_SECURITY_ADMIN_PASSWORD:-admin}`. [docker-compose.full.yml]
- [ ] [Review][Patch] F12: ACTION ITEM — `grafana/tempo:latest`, `grafana/alloy:latest`, `pactfoundation/pact-broker:latest` use unpinned tags. Pin to explicit versions before production use. [docker-compose.full.yml]

## Dev Agent Record

### Agent Model Used

_claude-sonnet-4-6_

### Debug Log References

- Task 8: `org.springframework.grpc.observation.ObservationGrpcClientInterceptor` NOT found in spring-grpc-core-1.0.2. Used fallback `io.micrometer.core.instrument.binder.grpc.ObservationGrpcClientInterceptor` from micrometer-core (confirmed present in micrometer-core-1.15.1.jar).
- Task 14/AdminPushService tests: Tests failed because `@InjectMocks` couldn't inject `Tracer` (field was null) and `verify` calls used 2-arg overload but implementation now uses 3-arg `convertAndSend`. Fixed by adding `@Mock Tracer tracer` and using `any(MessageHeaders.class)` matcher.
- Note: Added `spring-kafka` as optional dependency to common-lib to support Kafka interceptor classes.

### Completion Notes List

- **Part A (AC1):** Created `docker-compose.full.yml` override with Tempo, Loki, Alloy, Prometheus (port 9091), Grafana, Pact Broker — all on `full` profile. Created config files: `grafana/provisioning/datasources/datasources.yml`, `grafana/provisioning/dashboards/dashboards.yml`, `prometheus/prometheus.yml`, `tempo/tempo.yml`.
- **Part B (AC2):** Replaced `io.micrometer:micrometer-tracing` with `spring-boot-starter-opentelemetry` in `common-lib/pom.xml` (+ added `spring-kafka` optional for Kafka interceptors). Added `spring-boot-starter-opentelemetry` to all 7 service POMs. For `notification-service`, removed conflicting `spring-boot-micrometer-tracing-brave`.
- **Part C (AC2):** Added `management.tracing.sampling.probability=1.0`, `management.otlp.tracing.endpoint`, `management.endpoints.web.exposure.include` to base section of all 7 service `application.yml` files. Added `MANAGEMENT_OTLP_TRACING_ENDPOINT=http://tempo:4318/v1/traces` env var to all 7 app services in `docker-compose.yml`.
- **Part D (AC3):** Deleted `TracerConfig.java` (NOOP bean). Created `TracingConfig.java` with `ObservationGrpcClientInterceptor` and `ObservationGrpcServerInterceptor` beans using `io.micrometer.core.instrument.binder.grpc` package. Created `CorrelationIdKafkaProducerInterceptor` and `CorrelationIdKafkaConsumerInterceptor` in `common-lib/kafka`. Added `interceptor.classes` + `observation-enabled` to Kafka sections of all relevant services.
- **Part E (AC3):** Updated `AdminPushService.java` to inject `Tracer`, add `getTraceId()`/`buildHeaders()` helpers, and propagate `x-trace-id` STOMP header to all 3 push methods.
- **Part F (AC4):** Verified `ProductRestController` already has `Tracer` injection and `getTraceId()` — no changes needed. OTel bridge will provide real trace IDs now.
- **Part G Tests:** Created `CorrelationIdKafkaProducerInterceptorTest` (4 tests: add header, no header when MDC empty, no overwrite existing, blank MDC). Fixed `AdminPushServiceTest` to add `@Mock Tracer` and use `any(MessageHeaders.class)` matcher. All 222 tests across 4 services pass.

### Change Log

- 2026-04-17: Story 9.1 implemented — distributed tracing and correlation ID propagation (OpenTelemetry + Grafana Tempo + Kafka/gRPC/WebSocket trace propagation)

### File List

**New files:**
- `infra/docker/docker-compose.full.yml`
- `infra/docker/grafana/provisioning/datasources/datasources.yml`
- `infra/docker/grafana/provisioning/dashboards/dashboards.yml`
- `infra/docker/prometheus/prometheus.yml`
- `infra/docker/tempo/tempo.yml`
- `backend/common-lib/src/main/java/com/robomart/common/config/TracingConfig.java`
- `backend/common-lib/src/main/java/com/robomart/common/kafka/CorrelationIdKafkaProducerInterceptor.java`
- `backend/common-lib/src/main/java/com/robomart/common/kafka/CorrelationIdKafkaConsumerInterceptor.java`
- `backend/common-lib/src/test/java/com/robomart/common/kafka/CorrelationIdKafkaProducerInterceptorTest.java`

**Deleted files:**
- `backend/common-lib/src/main/java/com/robomart/common/config/TracerConfig.java`

**Modified files:**
- `backend/common-lib/pom.xml` — replaced `micrometer-tracing` with `spring-boot-starter-opentelemetry`; added `spring-kafka` optional
- `backend/api-gateway/pom.xml` — added `spring-boot-starter-opentelemetry`
- `backend/product-service/pom.xml` — added `spring-boot-starter-opentelemetry`
- `backend/cart-service/pom.xml` — added `spring-boot-starter-opentelemetry`
- `backend/order-service/pom.xml` — added `spring-boot-starter-opentelemetry`
- `backend/inventory-service/pom.xml` — added `spring-boot-starter-opentelemetry`
- `backend/payment-service/pom.xml` — added `spring-boot-starter-opentelemetry`
- `backend/notification-service/pom.xml` — replaced `spring-boot-micrometer-tracing-brave` with `spring-boot-starter-opentelemetry`
- `backend/api-gateway/src/main/resources/application.yml` — added management.tracing + otlp + prometheus exposure
- `backend/product-service/src/main/resources/application.yml` — added management.tracing + otlp; Kafka producer interceptor + observation
- `backend/cart-service/src/main/resources/application.yml` — added management.tracing + otlp + prometheus exposure
- `backend/order-service/src/main/resources/application.yml` — added management.tracing + otlp; Kafka producer+consumer interceptors + observation
- `backend/inventory-service/src/main/resources/application.yml` — added management.tracing + otlp; Kafka producer+consumer interceptors + observation
- `backend/payment-service/src/main/resources/application.yml` — added management.tracing + otlp; Kafka producer+consumer interceptors + observation
- `backend/notification-service/src/main/resources/application.yml` — added management.tracing + otlp; Kafka consumer interceptor + observation
- `infra/docker/docker-compose.yml` — added `MANAGEMENT_OTLP_TRACING_ENDPOINT` env var to all 7 app services
- `backend/notification-service/src/main/java/com/robomart/notification/service/AdminPushService.java` — added Tracer injection, getTraceId(), buildHeaders(), x-trace-id STOMP header
- `backend/notification-service/src/test/java/com/robomart/notification/unit/AdminPushServiceTest.java` — added Tracer mock, updated verify calls to 3-arg convertAndSend
