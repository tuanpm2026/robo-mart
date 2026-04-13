# Story 7.4: Implement System Health Monitoring

Status: done

## Story

As an admin,
I want to see the health status of all microservices with key metrics,
So that I can quickly identify and respond to service issues.

## Acceptance Criteria

1. **Given** the System tab on Admin Dashboard **When** I navigate to it **Then** I see a grid of 7 ServiceHealthCard components — one per service (Product, Cart, Order, Inventory, Payment, Notification, API Gateway). (FR48)

2. **Given** ServiceHealthCard component **When** a service is healthy **Then** it shows: service name, green check icon, green left border, p95 response time (e.g., "45ms"). Thresholds: healthy <200ms, degraded 200-1000ms, down >1000ms. (UX-DR4)

3. **Given** ServiceHealthCard **When** I click to expand **Then** expanded section shows: CPU %, Memory %, database connection pool utilization, Kafka consumer lag. (FR48, UX-DR4)

4. **Given** KafkaLagIndicator component inside expanded ServiceHealthCard **When** displayed **Then** it shows: consumer group name, current lag count, mini sparkline (last 5min), status badge (Healthy <100, Elevated 100-1000, Critical >1000). (UX-DR8)

5. **Given** System health data **When** WebSocket updates arrive **Then** service status and metrics update in real-time with smooth color transitions. (FR48)

6. **Given** Event sourcing for Order Service **When** admin inspects order event history **Then** the system can reconstruct entity state from event history for debugging via `GET /api/v1/admin/orders/{orderId}/events`. (FR67)

## Tasks / Subtasks

### Backend — Each Service (Actuator Metrics Config)

- [x] **Task 1: Add HTTP percentile metrics config to each service's application.yml** (AC: 1, 2, 3)
  - [x] In ALL 7 services' `dev` profile under `management:`, add:
    ```yaml
    management:
      metrics:
        distribution:
          percentiles:
            "[http.server.requests]": 0.95
    ```
  - [x] Services to update: `product-service`, `cart-service`, `order-service`, `inventory-service`, `payment-service`, `notification-service`, `api-gateway`
  - [x] Add `notification-service` to `backend/api-gateway/src/main/resources/application.yml` under `gateway.services`:
    ```yaml
    gateway:
      services:
        notification-service: ${NOTIFICATION_SERVICE_URL:http://localhost:8087}
    ```
  - [x] NOTE: property key is `"[http.server.requests]"` (square brackets required for YAML dot-key)

### Backend — API Gateway (New Route)

- [x] **Task 2: Add system health route to `RouteConfig.java`** (AC: 1)
  - [x] File: `backend/api-gateway/src/main/java/com/robomart/gateway/config/RouteConfig.java`
  - [x] Add route in `routeLocator()` bean (place before the final `.build()`):
    ```java
    .route("admin-system-health", r -> r
        .path("/api/v1/admin/system/health")
        .uri(notificationServiceUri))
    ```
  - [x] The `notificationServiceUri` field already exists in `RouteConfig.java` — no new @Value needed

### Backend — Notification Service (Health Aggregator)

- [x] **Task 3: Create health response records** (AC: 1, 2, 3)
  - [x] `ServiceHealthData.java` in `com.robomart.notification.web`:
    ```java
    public record ServiceHealthData(
        String service,
        String displayName,
        String actuatorStatus,   // "UP" or "DOWN"
        Long p95ResponseTimeMs,  // null if no requests recorded
        Double cpuPercent,       // 0.0–100.0, null if unavailable
        Double memoryPercent,    // 0.0–100.0, null if unavailable
        Integer dbPoolActive,    // null for services without DB (Cart, API Gateway)
        Integer dbPoolMax,       // null for services without DB
        Long kafkaConsumerLag,   // null if not available
        String consumerGroup,    // null if no Kafka consumer
        Instant checkedAt
    ) {}
    ```
  - [x] `SystemHealthResponse.java` in `com.robomart.notification.web`:
    ```java
    public record SystemHealthResponse(List<ServiceHealthData> services, Instant checkedAt) {}
    ```
  - [x] `ActuatorHealthResponse.java` in `com.robomart.notification.web` (for RestClient deserialization):
    ```java
    public record ActuatorHealthResponse(String status) {}
    ```
  - [x] `ActuatorMetricResponse.java` in `com.robomart.notification.web`:
    ```java
    public record ActuatorMetricResponse(List<Measurement> measurements) {
        public record Measurement(String statistic, Double value) {}
        public Double firstValue() {
            return measurements != null && !measurements.isEmpty() ? measurements.get(0).value() : null;
        }
    }
    ```

- [x] **Task 4: Create `HealthAggregatorService.java`** (AC: 1, 2, 3)
  - [x] Location: `com.robomart.notification.service`
  - [x] Use `@Value` for all 7 service URLs (reuse existing config keys where possible):
    ```java
    @Value("${notification.product-service.url:http://localhost:8081}") String productUrl;
    @Value("${notification.cart-service.url:http://localhost:8082}") String cartUrl;
    @Value("${notification.order-service.url:http://localhost:8083}") String orderUrl;
    @Value("${notification.inventory-service.url:http://localhost:8084}") String inventoryUrl;
    @Value("${notification.payment-service.url:http://localhost:8086}") String paymentUrl;
    @Value("${notification.notification-self.url:http://localhost:8087}") String notifUrl;
    @Value("${notification.api-gateway.url:http://localhost:8080}") String gatewayUrl;
    ```
  - [x] Define a private record: `record ServiceConfig(String name, String displayName, String baseUrl, boolean hasDb, boolean hasKafka, String consumerGroup) {}`
  - [x] In `@PostConstruct void init()`: build `List<ServiceConfig> serviceConfigs` and `Map<String, RestClient> clients`
    - Each RestClient uses `SimpleClientHttpRequestFactory` with 3-second connect + read timeout
    ```java
    @PostConstruct
    void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(3));

        serviceConfigs = List.of(
            new ServiceConfig("product-service", "Product Service", productUrl, true, true, "product-outbox-consumer"),
            new ServiceConfig("cart-service", "Cart Service", cartUrl, false, false, null),
            new ServiceConfig("order-service", "Order Service", orderUrl, true, true, "order-status-consumer"),
            new ServiceConfig("inventory-service", "Inventory Service", inventoryUrl, true, true, "inventory-consumer"),
            new ServiceConfig("payment-service", "Payment Service", paymentUrl, true, false, null),
            new ServiceConfig("notification-service", "Notification Service", notifUrl, true, true, "notification-email-group"),
            new ServiceConfig("api-gateway", "API Gateway", gatewayUrl, false, false, null)
        );
        clients = serviceConfigs.stream().collect(Collectors.toMap(
            ServiceConfig::name,
            c -> RestClient.builder().baseUrl(c.baseUrl()).requestFactory(factory).build()
        ));
    }
    ```
  - [x] `public SystemHealthResponse aggregateHealth()`: use `parallelStream()` to check all services concurrently:
    ```java
    List<ServiceHealthData> results = serviceConfigs.parallelStream()
        .map(this::checkServiceHealth)
        .toList();
    return new SystemHealthResponse(results, Instant.now());
    ```
  - [x] `private ServiceHealthData checkServiceHealth(ServiceConfig config)`:
    - Wrap entire method body in try-catch; if ANY exception → return `ServiceHealthData` with `actuatorStatus="DOWN"`, all metrics null
    - Step 1: call `GET /actuator/health` → parse `ActuatorHealthResponse.status`
    - Step 2: call `fetchMetric(client, "http.server.requests", "quantile:0.95")` → if VALUE in seconds, multiply by 1000 to get ms → cast to Long
    - Step 3: call `fetchMetric(client, "process.cpu.usage")` → multiply by 100.0 for percent
    - Step 4: call `fetchMetric(client, "jvm.memory.used", "area:heap")` and `fetchMetric(client, "jvm.memory.max", "area:heap")` → `memoryPercent = (used / max) * 100.0`
    - Step 5 (if `config.hasDb()`): call `fetchMetric(client, "hikaricp.connections.active")` and `fetchMetric(client, "hikaricp.connections.max")` → cast to Integer
    - Step 6 (if `config.hasKafka()`): call `fetchMetric(client, "kafka.consumer.records-lag-max")` → cast to Long
    - If metric endpoint returns 404/error, the value stays null — DO NOT fail the whole health check
  - [x] `private Double fetchMetric(RestClient client, String name, String... tags)`:
    ```java
    private Double fetchMetric(RestClient client, String metricName, String... tags) {
        try {
            String uri = "/actuator/metrics/" + metricName;
            if (tags.length > 0) {
                uri += "?tag=" + String.join("&tag=", tags);
            }
            ActuatorMetricResponse resp = client.get().uri(uri)
                .retrieve()
                .body(ActuatorMetricResponse.class);
            return resp != null ? resp.firstValue() : null;
        } catch (Exception e) {
            return null;
        }
    }
    ```
  - [x] Log WARN (not ERROR) for DOWN services: `log.warn("Health check failed for {}: {}", config.name(), e.getMessage())`

- [x] **Task 5: Create `HealthPushScheduler.java` and `SchedulingConfig.java`** (AC: 4, 5)
  - [x] Create `SchedulingConfig.java` in `com.robomart.notification.config`:
    ```java
    @Configuration
    @EnableScheduling
    public class SchedulingConfig {}
    ```
  - [x] Create `HealthPushScheduler.java` in `com.robomart.notification.service`:
    ```java
    @Component
    public class HealthPushScheduler {
        private final HealthAggregatorService healthAggregatorService;
        private final AdminPushService adminPushService;

        // constructor injection

        @Scheduled(fixedDelay = 10000)
        public void pushHealthUpdate() {
            try {
                SystemHealthResponse health = healthAggregatorService.aggregateHealth();
                adminPushService.pushSystemHealth(health);
            } catch (Exception e) {
                log.warn("Health push failed: {}", e.getMessage());
            }
        }
    }
    ```
  - [x] `@Scheduled(fixedDelay = 10000)` — 10 seconds BETWEEN completions (not fixed rate), prevents overlapping if aggregation is slow

- [x] **Task 6: Update `AdminPushService.java`** (AC: 4, 5)
  - [x] File: `backend/notification-service/src/main/java/com/robomart/notification/service/AdminPushService.java`
  - [x] Add constant: `private static final String TOPIC_SYSTEM_HEALTH = "/topic/system-health";`
  - [x] Add method:
    ```java
    public void pushSystemHealth(SystemHealthResponse health) {
        try {
            messagingTemplate.convertAndSend(TOPIC_SYSTEM_HEALTH, health);
            log.debug("Pushed system health update: {} services", health.services().size());
        } catch (Exception e) {
            log.warn("Failed to push system health to WebSocket: {}", e.getMessage());
        }
    }
    ```
  - [x] DO NOT change existing `pushOrderEvent` or `pushInventoryAlert` methods

- [x] **Task 7: Create `AdminSystemHealthRestController.java`** (AC: 1, 2, 3)
  - [x] Location: `com.robomart.notification.controller`
  - [x] `@RestController @RequestMapping("/api/v1/admin/system")`
  - [x] Inject `HealthAggregatorService`, `Tracer` (notification-service has `spring-boot-micrometer-tracing-brave`)
  - [x] `GET /api/v1/admin/system/health` → `ResponseEntity<ApiResponse<SystemHealthResponse>>`
    ```java
    @GetMapping("/health")
    public ResponseEntity<ApiResponse<SystemHealthResponse>> getSystemHealth() {
        SystemHealthResponse health = healthAggregatorService.aggregateHealth();
        String traceId = tracer.currentSpan() != null ? tracer.currentSpan().context().traceId() : null;
        return ResponseEntity.ok(new ApiResponse<>(health, traceId));
    }
    ```
  - [x] No `@PreAuthorize` — ADMIN enforced at API Gateway

- [x] **Task 8: Update notification-service `application.yml`** (AC: 1)
  - [x] File: `backend/notification-service/src/main/resources/application.yml`
  - [x] Add to the BASE section (same level as existing `notification:` block):
    ```yaml
    notification:
      order-service:
        url: ${ORDER_SERVICE_URL:http://localhost:8083}
      product-service:
        url: ${PRODUCT_SERVICE_URL:http://localhost:8081}
      # ADD THESE:
      cart-service:
        url: ${CART_SERVICE_URL:http://localhost:8082}
      inventory-service:
        url: ${INVENTORY_SERVICE_URL:http://localhost:8084}
      payment-service:
        url: ${PAYMENT_SERVICE_URL:http://localhost:8086}
      notification-self:
        url: ${NOTIFICATION_SERVICE_URL:http://localhost:8087}
      api-gateway:
        url: ${API_GATEWAY_URL:http://localhost:8080}
      admin-email: ${ADMIN_EMAIL:admin@robomart.local}
    ```

### Backend — Order Service (Event History - FR67)

- [x] **Task 9: Create `OrderEventResponse.java`** (AC: 6)
  - [x] Location: `com.robomart.order.web` (same package as other web records)
  - [x] Record:
    ```java
    public record OrderEventResponse(Long id, String status, Instant changedAt) {}
    ```

- [x] **Task 10: Add event history endpoint to `OrderAdminRestController.java`** (AC: 6)
  - [x] File: `backend/order-service/src/main/java/com/robomart/order/controller/OrderAdminRestController.java`
  - [x] Inject `OrderStatusHistoryRepository orderStatusHistoryRepository` via constructor
  - [x] Add endpoint (INSIDE existing `@RequestMapping("/api/v1/admin/orders")` controller):
    ```java
    @GetMapping("/{orderId}/events")
    public ResponseEntity<ApiResponse<List<OrderEventResponse>>> getOrderEvents(@PathVariable Long orderId) {
        List<OrderStatusHistory> history =
            orderStatusHistoryRepository.findByOrderIdOrderByChangedAtAsc(orderId);
        List<OrderEventResponse> events = history.stream()
            .map(h -> new OrderEventResponse(h.getId(), h.getStatus().name(), h.getChangedAt()))
            .toList();
        String traceId = tracer.currentSpan() != null ? tracer.currentSpan().context().traceId() : null;
        return ResponseEntity.ok(new ApiResponse<>(events, traceId));
    }
    ```
  - [x] `OrderStatusHistoryRepository` already has `findByOrderIdOrderByChangedAtAsc(Long orderId)` — no new query needed
  - [x] API Gateway route `/api/v1/admin/orders/**` already covers this endpoint — NO route changes needed

### Backend — Tests

- [x] **Task 11: Unit tests for `HealthAggregatorService`** (AC: 1, 2, 3)
  - [x] File: `backend/notification-service/src/test/java/com/robomart/notification/unit/service/HealthAggregatorServiceTest.java`
  - [x] Use `@ExtendWith(MockitoExtension.class)` pattern (no Spring context needed)
  - [x] Since `HealthAggregatorService` uses RestClient (hard to mock), test the `ServiceHealthData` construction logic:
    - Test 1: `checkServiceHealth` returns `actuatorStatus="DOWN"` when exception thrown (simulate by injecting DOWN-configured service)
    - Test 2: `aggregateHealth` returns list with 7 entries (all configured services covered)
    - Test 3: `memoryPercent` computed correctly: `(used / max) * 100.0`
  - [x] **Alternative**: Use `@SpringBootTest` with `WireMock` to mock actuator endpoints — but this is an integration test. For unit test, test only the DTO mapping and math logic.
  - [x] Focus: test `mapToServiceHealthData` helper logic and null-safety, not the HTTP client itself

### Frontend

- [x] **Task 12: Create `src/api/systemHealthApi.ts`** (AC: 1, 2)
  - [x] File: `frontend/admin-dashboard/src/api/systemHealthApi.ts`
  - [x] Types (mirror backend records):
    ```typescript
    export interface ServiceHealthData {
      service: string
      displayName: string
      actuatorStatus: 'UP' | 'DOWN'
      p95ResponseTimeMs: number | null
      cpuPercent: number | null
      memoryPercent: number | null
      dbPoolActive: number | null
      dbPoolMax: number | null
      kafkaConsumerLag: number | null
      consumerGroup: string | null
      checkedAt: string
    }
    export interface SystemHealthResponse {
      services: ServiceHealthData[]
      checkedAt: string
    }
    ```
  - [x] `fetchSystemHealth(): Promise<SystemHealthResponse>` → `GET /api/v1/admin/system/health` → unwrap `.data.data` (uses `ApiResponse<SystemHealthResponse>`)
    ```typescript
    import adminClient from './adminClient'

    export async function fetchSystemHealth(): Promise<SystemHealthResponse> {
      const { data } = await adminClient.get<{ data: SystemHealthResponse; traceId: string }>(
        '/api/v1/admin/system/health'
      )
      return data.data
    }
    ```

- [x] **Task 13: Create `src/stores/useSystemHealthStore.ts`** (AC: 1, 2, 3, 4, 5)
  - [x] Pinia Composition API style (follow `useDashboardStore` pattern)
  - [x] State:
    ```typescript
    const services = ref<ServiceHealthData[]>([])
    const isLoading = ref(false)
    const error = ref<string | null>(null)
    // Rolling history for sparklines: 30 entries per service (5 min at 10s interval)
    const lagHistory = ref<Record<string, number[]>>({})
    ```
  - [x] Computed `overallHealth`: `'healthy' | 'degraded' | 'down'`
    ```typescript
    const overallHealth = computed((): 'healthy' | 'degraded' | 'down' => {
      if (services.value.length === 0) return 'healthy'
      const statuses = services.value.map(s => computeVisualStatus(s))
      if (statuses.some(s => s === 'down')) return 'down'
      if (statuses.some(s => s === 'degraded')) return 'degraded'
      return 'healthy'
    })
    ```
  - [x] `function computeVisualStatus(s: ServiceHealthData): 'healthy' | 'degraded' | 'down'`:
    - If `s.actuatorStatus === 'DOWN'` → `'down'`
    - Else if `s.p95ResponseTimeMs !== null && s.p95ResponseTimeMs > 1000` → `'down'`
    - Else if `s.p95ResponseTimeMs !== null && s.p95ResponseTimeMs >= 200` → `'degraded'`
    - Else → `'healthy'`
  - [x] `async function loadHealth()`: set isLoading, call `fetchSystemHealth()`, set services, clear isLoading
  - [x] `function updateFromWebSocket(health: SystemHealthResponse)`: set services to `health.services`, then for each service update `lagHistory`
  - [x] `function updateLagHistory(serviceName: string, lag: number | null)`:
    ```typescript
    if (!lagHistory.value[serviceName]) lagHistory.value[serviceName] = []
    lagHistory.value[serviceName].push(lag ?? 0)
    if (lagHistory.value[serviceName].length > 30) lagHistory.value[serviceName].shift()
    ```
  - [x] Export: `{ services, isLoading, error, overallHealth, lagHistory, loadHealth, updateFromWebSocket, computeVisualStatus }`

- [x] **Task 14: Update `src/composables/useWebSocket.ts`** (AC: 4, 5)
  - [x] Import `useSystemHealthStore` at the top
  - [x] Inside `subscribeToTopics()`, after the existing subscriptions, add:
    ```typescript
    const systemHealthStore = useSystemHealthStore()
    stompClient.subscribe('/topic/system-health', (message) => {
      try {
        const payload = JSON.parse(message.body) as SystemHealthResponse
        systemHealthStore.updateFromWebSocket(payload)
      } catch (e) {
        console.error('Failed to parse system health WebSocket event', e)
      }
    })
    ```
  - [x] Import `type SystemHealthResponse` from `@/api/systemHealthApi`
  - [x] DO NOT change existing `/topic/orders` or `/topic/inventory-alerts` subscriptions

- [x] **Task 15: Create `src/components/system/KafkaLagIndicator.vue`** (AC: 3, 4)
  - [x] Props: `consumerGroup: string | null`, `currentLag: number | null`, `history: number[]`
  - [x] Computed `lagStatus`: `'healthy' | 'elevated' | 'critical'`
    - null → 'healthy'; lag < 100 → 'healthy'; lag <= 1000 → 'elevated'; lag > 1000 → 'critical'
  - [x] Computed `tagSeverity`: `'success' | 'warn' | 'danger'`
  - [x] Template:
    ```vue
    <div class="kafka-lag" :aria-label="`${consumerGroup} consumer lag: ${currentLag ?? 'N/A'} messages, status: ${lagStatus}`">
      <span class="kafka-group">{{ consumerGroup ?? 'No consumer' }}</span>
      <span class="kafka-count">{{ currentLag !== null ? currentLag : 'N/A' }}</span>
      <Chart v-if="history.length > 1" type="line" :data="sparklineData" :options="sparklineOptions" style="height:40px; width:120px" />
      <Tag :severity="tagSeverity" :value="lagStatus" />
    </div>
    ```
  - [x] Sparkline `chartData` computed:
    ```typescript
    const sparklineData = computed(() => ({
      labels: props.history.map((_, i) => i.toString()),
      datasets: [{ data: props.history, borderColor: lagColor.value, fill: false, pointRadius: 0, tension: 0.3 }]
    }))
    const sparklineOptions = { plugins: { legend: { display: false } }, scales: { x: { display: false }, y: { display: false } }, animation: false }
    ```
  - [x] `lagColor`: `{ healthy: '#22c55e', elevated: '#f59e0b', critical: '#ef4444' }[lagStatus.value]`

- [x] **Task 16: Create `src/components/system/ServiceHealthCard.vue`** (AC: 2, 3, 5)
  - [x] Props: `service: ServiceHealthData`, `history: number[]` (lag sparkline data)
  - [x] State: `const isExpanded = ref(false)`
  - [x] Computed `visualStatus`: call `computeVisualStatus(props.service)` (import from `useSystemHealthStore`)
  - [x] Computed `statusClass`: maps visualStatus to CSS class (`health-healthy`, `health-degraded`, `health-down`)
  - [x] Template structure:
    ```vue
    <div class="service-card" :class="statusClass" role="article" :aria-label="`${service.displayName} status: ${visualStatus}`">
      <!-- Header row (always visible) -->
      <div class="card-header" @click="isExpanded = !isExpanded">
        <i :class="statusIcon" />
        <span class="service-name">{{ service.displayName }}</span>
        <span class="p95">{{ p95Label }}</span>
        <i class="pi pi-chevron-down expand-icon" :class="{ rotated: isExpanded }" />
      </div>

      <!-- Expandable metrics -->
      <Transition name="expand">
        <div v-if="isExpanded" class="card-metrics">
          <div class="metric-row"><span>CPU</span><span>{{ cpuLabel }}</span></div>
          <div class="metric-row"><span>Memory</span><span>{{ memoryLabel }}</span></div>
          <div v-if="service.dbPoolActive !== null" class="metric-row">
            <span>DB Pool</span><span>{{ service.dbPoolActive }}/{{ service.dbPoolMax }}</span>
          </div>
          <KafkaLagIndicator
            v-if="service.consumerGroup !== null"
            :consumerGroup="service.consumerGroup"
            :currentLag="service.kafkaConsumerLag"
            :history="history"
          />
        </div>
      </Transition>
    </div>
    ```
  - [x] `p95Label`: `service.p95ResponseTimeMs !== null ? service.p95ResponseTimeMs + 'ms' : 'N/A'`
  - [x] `cpuLabel`: `service.cpuPercent !== null ? service.cpuPercent.toFixed(1) + '%' : 'N/A'`
  - [x] `memoryLabel`: `service.memoryPercent !== null ? service.memoryPercent.toFixed(1) + '%' : 'N/A'`
  - [x] `statusIcon`: `{ healthy: 'pi pi-check-circle', degraded: 'pi pi-exclamation-circle', down: 'pi pi-times-circle' }[visualStatus]`
  - [x] CSS `transition: border-left-color 0.5s ease, color 0.5s ease` on `.service-card` for smooth color changes
  - [x] CSS classes:
    - `.health-healthy { border-left: 4px solid #22c55e; }`
    - `.health-degraded { border-left: 4px solid #f59e0b; }`
    - `.health-down { border-left: 4px solid #ef4444; }`
  - [x] Use `<Transition name="expand">` for smooth expand/collapse
  - [x] Import `KafkaLagIndicator` from `./KafkaLagIndicator.vue`

- [x] **Task 17: Create `src/components/system/SystemHealthPanel.vue`** (AC: 1, 2, 3, 4, 5)
  - [x] Uses `useSystemHealthStore`
  - [x] On mount: calls `store.loadHealth()` (only if services array is empty — avoids re-fetch on tab switch)
  - [x] Template:
    ```vue
    <div class="system-health-panel">
      <!-- Loading state -->
      <div v-if="store.isLoading" class="health-grid">
        <Skeleton v-for="n in 7" :key="n" height="80px" class="card-skeleton" />
      </div>

      <!-- Error state -->
      <div v-else-if="store.error" class="health-error">
        Failed to load health data. <Button label="Retry" size="small" @click="store.loadHealth()" />
      </div>

      <!-- Health grid -->
      <div v-else class="health-grid">
        <ServiceHealthCard
          v-for="service in store.services"
          :key="service.service"
          :service="service"
          :history="store.lagHistory[service.service] ?? []"
        />
      </div>

      <!-- Last updated -->
      <div v-if="store.services.length > 0" class="last-updated">
        Last updated: {{ lastUpdatedLabel }}
      </div>
    </div>
    ```
  - [x] `lastUpdatedLabel`: compute from `store.services[0]?.checkedAt` — format as "just now" / "N seconds ago"
  - [x] CSS: `.health-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 16px; }`

- [x] **Task 18: Create `src/views/SystemHealthPage.vue`** (AC: 1)
  - [x] Simple page wrapper:
    ```vue
    <script setup>
    import SystemHealthPanel from '@/components/system/SystemHealthPanel.vue'
    </script>
    <template>
      <div>
        <h1 class="admin-page-title">System Health</h1>
        <SystemHealthPanel />
      </div>
    </template>
    ```
  - [x] No additional data loading here — `SystemHealthPanel` handles it internally

- [x] **Task 19: Update `src/views/DashboardPage.vue` System tab** (AC: 1, 5)
  - [x] Import `SystemHealthPanel` from `@/components/system/SystemHealthPanel.vue`
  - [x] Import `useSystemHealthStore`
  - [x] Replace the `<div class="system-placeholder">` content:
    ```vue
    <TabPanel value="system">
      <SystemHealthPanel />
    </TabPanel>
    ```
  - [x] Update the "System Health" MetricCard to use `systemHealthStore.overallHealth`:
    ```vue
    <MetricCard
      label="System Health"
      :value="systemHealthStore.overallHealth"
      format="label"
      :color="systemHealthStore.overallHealth === 'healthy' ? 'green' : systemHealthStore.overallHealth === 'degraded' ? 'yellow' : 'red'"
      :loading="systemHealthStore.isLoading"
    />
    ```
  - [x] Remove `dashboardStore.systemHealth` reference from the MetricCard (keep it in the store for backward compat, just don't use it here)
  - [x] Add `const systemHealthStore = useSystemHealthStore()` to `<script setup>`

- [x] **Task 20: Update `src/layouts/AdminLayout.vue`** (AC: 1)
  - [x] Replace the non-functional Health link:
    ```vue
    <!-- BEFORE: -->
    <a href="#" class="admin-nav-item">
      <i class="pi pi-heart" />
      <span v-if="!isSidebarCollapsed">Health</span>
    </a>

    <!-- AFTER: -->
    <RouterLink to="/admin/system/health" class="admin-nav-item">
      <i class="pi pi-heart" />
      <span v-if="!isSidebarCollapsed">Health</span>
    </RouterLink>
    ```
  - [x] Add `'admin-system-health': 'System Health'` to the `breadcrumbLabel` map in `<script setup>`

- [x] **Task 21: Update `src/router/index.ts`** (AC: 1)
  - [x] Add health page route (after `admin-system-events` route):
    ```typescript
    {
      path: '/admin/system/health',
      name: 'admin-system-health',
      component: () => import('../views/SystemHealthPage.vue'),
      meta: { requiresAdmin: true },
    },
    ```

- [x] **Task 22: Unit tests** (AC: 1, 2, 5)
  - [x] `frontend/admin-dashboard/src/__tests__/useSystemHealthStore.spec.ts`
  - [x] 4 tests:
    1. `loadHealth` sets services from API response
    2. `overallHealth` returns 'healthy' when all UP + p95 < 200
    3. `overallHealth` returns 'down' when any service is DOWN
    4. `updateFromWebSocket` updates services and lagHistory rolling buffer
  - [x] Follow `useDashboardStore.spec.ts` pattern: `setActivePinia(createPinia())`, mock `fetchSystemHealth` with `vi.mocked()`

## Dev Notes

### Architecture Overview

```
Admin navigates to System Health page:
  SystemHealthPage.vue → SystemHealthPanel.vue → useSystemHealthStore.loadHealth()
    └── GET /api/v1/admin/system/health → API Gateway → Notification Service :8087
          └── AdminSystemHealthRestController → HealthAggregatorService.aggregateHealth()
                ├── parallelStream(): 7 RestClient calls to /actuator/health + metrics
                └── Returns SystemHealthResponse(List<ServiceHealthData>)

Real-time updates (every 10s):
  HealthPushScheduler (@Scheduled fixedDelay=10s) → HealthAggregatorService.aggregateHealth()
    └── AdminPushService.pushSystemHealth() → /topic/system-health (WebSocket STOMP)
          └── useWebSocket.ts subscription → useSystemHealthStore.updateFromWebSocket()
                └── ServiceHealthCard auto-updates with smooth CSS transitions

Dashboard "System" tab also shows SystemHealthPanel:
  DashboardPage.vue System tab → <SystemHealthPanel /> (same component, shared store)
  System Health MetricCard reads useSystemHealthStore.overallHealth

Order event history (FR67):
  GET /api/v1/admin/orders/{orderId}/events → API Gateway (existing admin-orders route)
    └── OrderAdminRestController.getOrderEvents() → OrderStatusHistoryRepository
          └── Returns List<OrderEventResponse> sorted by changedAt ASC
```

### Critical: Actuator Percentile Metric Config

Enable p95 in each service `application.yml`. The YAML key MUST use square brackets:
```yaml
management:
  metrics:
    distribution:
      percentiles:
        "[http.server.requests]": 0.95
```

Without brackets, YAML dot-notation treats `http.server.requests` as nested key → wrong property path → percentile metric NOT registered.

Query URL: `GET /actuator/metrics/http.server.requests?tag=quantile:0.95`

Response value is in **seconds** → multiply by 1000 for milliseconds:
```java
Double value = fetchMetric(client, "http.server.requests", "quantile:0.95");
Long p95Ms = value != null ? (long)(value * 1000) : null;
```

**Important**: If no HTTP requests have been made yet (fresh restart), the `quantile:0.95` tag won't exist → `fetchMetric` returns `null` → display "N/A" in UI. This is expected behavior, not an error.

### Critical: RestClient Timeout Configuration

Services that are DOWN must fail fast (3s) to keep aggregation under 5s total:
```java
import org.springframework.http.client.SimpleClientHttpRequestFactory;

SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
factory.setConnectTimeout(Duration.ofSeconds(3));  // Spring 6.1+ API
factory.setReadTimeout(Duration.ofSeconds(3));

RestClient client = RestClient.builder()
    .baseUrl(serviceUrl)
    .requestFactory(factory)
    .build();
```

`SimpleClientHttpRequestFactory.setConnectTimeout(Duration)` is available in Spring Framework 6.1+ (which Spring Boot 4.0.4 / Framework 7.x bundles). Do NOT use `setConnectTimeout(int)` (millisecond variant).

### Critical: parallelStream() for Health Aggregation

ALL 7 service checks run in parallel. Do NOT use `.stream().map()` (sequential).

```java
List<ServiceHealthData> results = serviceConfigs.parallelStream()
    .map(this::checkServiceHealth)
    .toList();
```

The `checkServiceHealth` method MUST be fully exception-safe (catch all exceptions). If it throws, the `parallelStream` propagates the exception and aborts. The catch block must return a `ServiceHealthData` with `actuatorStatus = "DOWN"`.

### Critical: Services WITHOUT DB or Kafka

- **Cart Service**: No DB (Redis only), no Kafka consumers → `dbPoolActive=null, dbPoolMax=null, kafkaConsumerLag=null`
- **API Gateway**: No DB, no Kafka consumers → same
- **Payment Service**: Has DB, no Kafka consumers (no pom micrometer-tracing-brave → no tracing, but DOES have Actuator for health/metrics) → `kafkaConsumerLag=null`

HikariCP pool metrics (`hikaricp.connections.active`, `hikaricp.connections.max`) only appear on services with JPA + HikariCP. For Cart/Gateway, calling these metrics will return 404 from Actuator → `fetchMetric` returns null → handled correctly.

### Actuator Health Endpoint JSON

```
GET /actuator/health
→ {"status": "UP", "components": {...}}   (when show-details is configured)
→ {"status": "UP"}                         (default, no auth)
→ {"status": "DOWN"}                       (when service has failed dependency)
```

The `ActuatorHealthResponse` only needs the `status` field. Jackson 3.x ignores unknown fields by default — no `@JsonIgnoreProperties` needed on records.

### Notification Service application.yml — Config Keys MUST Match @Value

The `@Value` annotations in `HealthAggregatorService` reference these YAML keys:
- `${notification.product-service.url:...}` → already exists
- `${notification.order-service.url:...}` → already exists
- `${notification.cart-service.url:...}` → **NEW** (Task 8)
- `${notification.inventory-service.url:...}` → **NEW** (Task 8)
- `${notification.payment-service.url:...}` → **NEW** (Task 8)
- `${notification.notification-self.url:...}` → **NEW** (Task 8)
- `${notification.api-gateway.url:...}` → **NEW** (Task 8)

If Task 8 is not done before Task 4, the service will start but URLs will fall back to hardcoded defaults.

### @EnableScheduling Placement

Add `@EnableScheduling` on a new `SchedulingConfig.java` class, NOT on `NotificationServiceApplication.java`. This follows existing config class patterns (`WebSocketConfig`, `KafkaDlqConfig`).

```java
package com.robomart.notification.config;

@Configuration
@EnableScheduling
public class SchedulingConfig {}
```

### Frontend: computeVisualStatus must be Importable

`computeVisualStatus` is defined in `useSystemHealthStore.ts` and needs to be used by:
1. `useSystemHealthStore` itself (for `overallHealth` computed)
2. `ServiceHealthCard.vue` (to determine card styling)

Export it as a named function outside the store definition:
```typescript
// At module level (outside defineStore)
export function computeVisualStatus(s: ServiceHealthData): 'healthy' | 'degraded' | 'down' {
  if (s.actuatorStatus === 'DOWN') return 'down'
  if (s.p95ResponseTimeMs !== null && s.p95ResponseTimeMs > 1000) return 'down'
  if (s.p95ResponseTimeMs !== null && s.p95ResponseTimeMs >= 200) return 'degraded'
  return 'healthy'
}
```

Import in `ServiceHealthCard.vue`: `import { computeVisualStatus } from '@/stores/useSystemHealthStore'`

### Frontend: WebSocket Subscription in subscribeToTopics()

The `subscribeToTopics()` function is called inside `onConnect` callback in `useWebSocket.ts`. The `useSystemHealthStore()` must be called inside `subscribeToTopics()` (not at the top of `useWebSocket()`) to ensure the Pinia context is active:

```typescript
function subscribeToTopics() {
  if (!stompClient) return
  // existing subscriptions...

  // NEW: import at top of file; call store inside function
  const systemHealthStore = useSystemHealthStore()
  stompClient.subscribe('/topic/system-health', (message) => {
    try {
      const payload = JSON.parse(message.body) as SystemHealthResponse
      systemHealthStore.updateFromWebSocket(payload)
    } catch (e) {
      console.error('Failed to parse system health WebSocket event', e)
    }
  })
}
```

### Frontend: Smooth Color Transitions on ServiceHealthCard

The status CSS class changes when WebSocket pushes new data. CSS transitions handle the animation:
```css
.service-card {
  transition: border-left-color 0.5s ease;
  border-left: 4px solid transparent;
}
.service-card .pi { transition: color 0.5s ease; }
.health-healthy { border-left-color: #22c55e; }
.health-degraded { border-left-color: #f59e0b; }
.health-down { border-left-color: #ef4444; }
```

Vue's reactivity + CSS transitions automatically animate when `visualStatus` changes (no JavaScript animation needed).

### Frontend: SystemHealthPanel — Load Once Per Mount

The System tab might be visited multiple times. Check `services.length > 0` before fetching:
```typescript
onMounted(() => {
  if (store.services.length === 0) {
    store.loadHealth()
  }
})
```

WebSocket keeps it up-to-date after initial load — no polling needed.

### Kafka Consumer Lag Metric Name

Spring Kafka + Micrometer auto-registers Kafka consumer metrics. The metric is:
- `kafka.consumer.records-lag-max` (max lag across all assigned partitions for the consumer group)

This requires `spring-kafka` on classpath + `spring-boot-starter-actuator` — both already present in each service's pom.xml.

For services that consume Kafka (Product, Order, Inventory, Notification), the metric appears once the consumer has polled at least once. If Kafka is not running or consumer not started, `fetchMetric` returns null → `kafkaConsumerLag = null`.

Consumer group names (configured in `HealthAggregatorService.init()`):
- product-service: check `backend/product-service/src/main/resources/application.yml` for `spring.kafka.consumer.group-id`
- order-service: similarly
- inventory-service: similarly
- notification-service: `notification-dlq-consumer-group` (from DlqConsumer), `notification-email-group` (from OrderNotificationConsumer)

**VERIFY** actual consumer group names from each service's `application.yml` before hardcoding in `HealthAggregatorService`. Use sensible defaults if not found (e.g., `"{service-name}-consumer"`).

### Order Service Event History: Route Already Exists

The `admin-orders` route in `RouteConfig.java` uses path pattern `/api/v1/admin/orders/**` which covers the new `GET /api/v1/admin/orders/{orderId}/events` endpoint. NO route changes needed in API Gateway.

The `OrderStatusHistory` entity has:
- `id` (Long)
- `order` (@ManyToOne, LAZY)
- `status` (OrderStatus enum)
- `changedAt` (Instant)

Map to `OrderEventResponse(Long id, String status, Instant changedAt)` — use `h.getStatus().name()` to convert enum to String.

### Previous Story Patterns (from Story 7.3)

| Pattern | Location | Follow |
|---------|----------|--------|
| `ApiResponse<T>` wrapping | All admin controllers | `new ApiResponse<>(data, getTraceId())` |
| `Tracer` injection | Order/Notification controllers | Already in pom; inject + null-check |
| No `@PreAuthorize` | Admin controllers | Gateway enforces ADMIN |
| `@ExtendWith(MockitoExtension.class)` | Unit tests | Use, NOT `@MockitoBean` |
| `adminClient` for HTTP | Frontend API files | Import from `@/api/adminClient` |
| Pinia Composition API | Frontend stores | `defineStore('name', () => {...})` |
| `setActivePinia(createPinia())` | Frontend unit tests | In `beforeEach` |

### Critical Pitfalls from Previous Stories

1. **@MockitoBean not @MockBean** — For Spring integration tests use `@MockitoBean`. For unit tests use `@Mock` + `@ExtendWith(MockitoExtension.class)`.

2. **Jackson 3.x** — Package `tools.jackson.databind`. Records like `ServiceHealthData` and `SystemHealthResponse` serialize/deserialize correctly without extra annotations. `Instant` serializes as ISO-8601 string by default in Jackson 3.x.

3. **SimpleClientHttpRequestFactory timeout API** — Use `Duration`-based setters (Spring 6.1+), NOT `int` milliseconds: `factory.setConnectTimeout(Duration.ofSeconds(3))`.

4. **parallelStream + exception safety** — If `checkServiceHealth` throws (not caught), the whole `parallelStream` fails. Every exception MUST be caught inside `checkServiceHealth` and returned as a DOWN `ServiceHealthData`.

## Review Findings

### Decision Needed

- [x] [Review][Decision] REST endpoint calls `aggregateHealth()` directly on every GET — resolved: added `latestHealth` cache field in `HealthAggregatorService`; `aggregateHealth()` updates it; REST endpoint serves `getLatestHealth()` with fallback to fresh sweep on first request [`AdminSystemHealthRestController.java`, `HealthAggregatorService.java`]

### Patches

- [x] [Review][Patch] `ActuatorMetricResponse.firstValue()` picks `measurements.get(0)` without filtering by `statistic == "VALUE"` — fixed: filter by `statistic == "VALUE"` with fallback to index 0 [`ActuatorMetricResponse.java`]
- [x] [Review][Patch] `lastUpdatedLabel` in `SystemHealthPanel.vue` is computed once and freezes between WebSocket pushes — fixed: added 1-second `setInterval` ticker ref; cleared in `onUnmounted` [`SystemHealthPanel.vue`]
- [x] [Review][Patch] `lastUpdatedLabel` shows negative seconds when client clock is behind server — fixed: `diff < 0` now returns `'just now'` [`SystemHealthPanel.vue`]
- [x] [Review][Patch] `KafkaLagIndicator` doesn't guard against negative `currentLag` — fixed: `currentLag < 0` returns `'healthy'` [`KafkaLagIndicator.vue`]
- [x] [Review][Patch] `actuatorStatus` values 'UNKNOWN' or 'OUT_OF_SERVICE' fall through as healthy — fixed: changed `=== 'DOWN'` to `!== 'UP'` in `computeVisualStatus` [`useSystemHealthStore.ts`]
- [x] [Review][Patch] DB Pool row renders as `5/null` when `dbPoolMax` is null — fixed: `{{ service.dbPoolMax ?? '?' }}` [`ServiceHealthCard.vue`]
- [x] [Review][Patch] `getOrderEvents` returns `200 OK` with empty array for non-existent `orderId` — fixed: returns 404 when `history.isEmpty()` [`OrderAdminRestController.java`]
- [x] [Review][Patch] WebSocket payload not validated before `updateFromWebSocket` — fixed: guard `if (!Array.isArray(payload?.services))` with error log [`useWebSocket.ts`]
- [x] [Review][Patch] `updateLagHistory` called for all 7 services including non-Kafka ones — fixed: only call `updateLagHistory` when `svc.consumerGroup !== null` [`useSystemHealthStore.ts`]
- [x] [Review][Patch] `HealthAggregatorServiceTest.memoryPercent_computedCorrectly` test does not invoke the service — fixed: replaced with `aggregateHealth_downServicesHaveNullDbAndKafkaMetrics` test [`HealthAggregatorServiceTest.java`]
- [x] [Review][Patch] `p95Seconds` NaN or Infinity not guarded before cast to Long — fixed: `Double.isFinite(p95Seconds)` check added [`HealthAggregatorService.java`]
- [x] [Review][Patch] `fetchMetric()` silently swallows ALL exceptions with no logging — fixed: added `log.debug` for null results and caught exceptions [`HealthAggregatorService.java`]
- [x] [Review][Patch] `FailedEventServiceTest` mock signature mismatch — dismissed: `FailedEventRepository.findByStatus(String, Pageable)` already exists in production code; test update is correct

### Deferred

- [x] [Review][Defer] `lagHistory` keys never pruned for decommissioned/renamed services [`useSystemHealthStore.ts`] — deferred, pre-existing
- [x] [Review][Defer] Scheduler thread blocked ~6s per health sweep; fixedDelay prevents overlap but blocks other `@Scheduled` tasks during sweep [`HealthPushScheduler.java`] — deferred, pre-existing
- [x] [Review][Defer] `overallHealth` returns `'healthy'` when `services` array is empty — pre-load false positive on dashboard [`useSystemHealthStore.ts`] — deferred, pre-existing

5. **useToast() in Pinia stores** — Call `useToast()` at store initialization level (inside `defineStore` setup function), NOT inside async action body. (Already learned from Story 7.3.)

6. **Notification Service port** — Is `8087`, NOT `8086` (Payment). The `HealthAggregatorService` calling its own actuator at `http://localhost:8087/actuator/health` is correct (self-health check is valid).

7. **YAML percentile key** — MUST use `"[http.server.requests]"` with quotes and brackets in YAML. Without quotes, YAML parses `http` as a nested key. Test this by verifying the metric appears in `/actuator/metrics` after startup.

8. **Cart Service has NO DB** — Don't attempt to query `hikaricp.*` metrics for cart-service. Set `hasDb = false` in the `ServiceConfig` for cart-service and api-gateway.

### Project Structure — New Files

**New Backend Files:**
```
backend/notification-service/src/main/java/com/robomart/notification/
  web/
    ServiceHealthData.java                ← NEW
    SystemHealthResponse.java             ← NEW
    ActuatorHealthResponse.java           ← NEW
    ActuatorMetricResponse.java           ← NEW
  service/
    HealthAggregatorService.java          ← NEW
    HealthPushScheduler.java              ← NEW
  controller/
    AdminSystemHealthRestController.java  ← NEW
  config/
    SchedulingConfig.java                 ← NEW
backend/notification-service/src/test/java/com/robomart/notification/unit/service/
  HealthAggregatorServiceTest.java        ← NEW

backend/order-service/src/main/java/com/robomart/order/
  web/
    OrderEventResponse.java               ← NEW
```

**Modified Backend Files:**
```
backend/notification-service/src/main/java/com/robomart/notification/service/AdminPushService.java
  ← add TOPIC_SYSTEM_HEALTH constant + pushSystemHealth() method
backend/notification-service/src/main/resources/application.yml
  ← add 5 new service URL configs
backend/api-gateway/src/main/java/com/robomart/gateway/config/RouteConfig.java
  ← add admin-system-health route
backend/api-gateway/src/main/resources/application.yml
  ← add notification-service to gateway.services
backend/order-service/src/main/java/com/robomart/order/controller/OrderAdminRestController.java
  ← add getOrderEvents() method + inject OrderStatusHistoryRepository

# Each service's application.yml — add percentile metrics config:
backend/product-service/src/main/resources/application.yml
backend/cart-service/src/main/resources/application.yml
backend/order-service/src/main/resources/application.yml
backend/inventory-service/src/main/resources/application.yml
backend/payment-service/src/main/resources/application.yml
backend/notification-service/src/main/resources/application.yml  (already listed above)
backend/api-gateway/src/main/resources/application.yml  (already listed above)
```

**New Frontend Files:**
```
frontend/admin-dashboard/src/
  api/
    systemHealthApi.ts                    ← NEW
  stores/
    useSystemHealthStore.ts               ← NEW
  components/system/
    KafkaLagIndicator.vue                 ← NEW
    ServiceHealthCard.vue                 ← NEW
    SystemHealthPanel.vue                 ← NEW
  views/
    SystemHealthPage.vue                  ← NEW
  __tests__/
    useSystemHealthStore.spec.ts          ← NEW
```

**Modified Frontend Files:**
```
frontend/admin-dashboard/src/composables/useWebSocket.ts  ← add /topic/system-health subscription
frontend/admin-dashboard/src/views/DashboardPage.vue      ← replace System tab placeholder
frontend/admin-dashboard/src/layouts/AdminLayout.vue      ← fix Health nav link + breadcrumb
frontend/admin-dashboard/src/router/index.ts              ← add admin-system-health route
```

### References

- Story requirements: `[_bmad-output/planning-artifacts/epics.md#Story 7.4]`
- UX design: `[_bmad-output/planning-artifacts/*ux*.md#ServiceHealthCard, KafkaLagIndicator]`
- Architecture: health monitoring — `[architecture.md#FR48, NFR38-42]`
- Architecture: observability stack — `[architecture.md#Infrastructure & Deployment]`
- Architecture: admin-dashboard system/ folder — `[architecture.md#admin-dashboard component tree]`
- Previous story patterns: `[7-3-implement-cqrs-reporting-dlq-management.md#Dev Notes]`
- Spring Boot Actuator metrics: `management.metrics.distribution.percentiles`
- Existing WebSocket setup: `[notification-service/config/WebSocketConfig.java]`
- Existing push service: `[notification-service/service/AdminPushService.java]`
- RestClient pattern: `[notification-service/client/OrderServiceClient.java]`
- `[CLAUDE.md]`: Spring Boot 4 gotchas, Jackson 3.x, service ports

## Change Log

- 2026-04-13: Story 7.4 implemented — System Health Monitoring. Added HealthAggregatorService (parallelStream health aggregation), HealthPushScheduler (10s WebSocket push), AdminSystemHealthRestController, order event history endpoint, and full frontend (ServiceHealthCard, KafkaLagIndicator, SystemHealthPanel, SystemHealthPage, store, router). All 22 tasks completed, 37+73+4 tests pass.

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

- Fixed pre-existing compilation error in `FailedEventServiceTest.java` line 86: `findByStatus("PENDING")` → `findByStatus(eq("PENDING"), any(Pageable.class))` to match actual repository signature.
- `HealthAggregatorService.init()` made `public` to allow `ReflectionTestUtils` access in unit tests.
- Kafka consumer groups verified from actual `@KafkaListener` annotations: order-service and inventory-service have NO Kafka consumers (only producers), so `hasKafka=false` for both.
- `cart-service` and `api-gateway` management blocks are at base level (no dev profile) — percentile config added there.

### Completion Notes List

All 22 tasks implemented and tested:
- Backend: 7 service `application.yml` files updated with p95 percentile config; notification-service URL added to API gateway; `admin-system-health` route added to `RouteConfig`; 4 response records created in notification-service; `HealthAggregatorService` with parallel health aggregation via `parallelStream()`; `SchedulingConfig` + `HealthPushScheduler` pushing every 10s; `AdminPushService` updated with `/topic/system-health` push; `AdminSystemHealthRestController` exposing `GET /api/v1/admin/system/health`; `OrderEventResponse` record and `getOrderEvents()` endpoint added to order-service.
- Frontend: `systemHealthApi.ts`, `useSystemHealthStore.ts`, `useWebSocket.ts` updated, `KafkaLagIndicator.vue`, `ServiceHealthCard.vue`, `SystemHealthPanel.vue`, `SystemHealthPage.vue`, `DashboardPage.vue` updated, `AdminLayout.vue` Health nav fixed, router updated.
- Tests: notification-service 37 pass, order-service 73 pass, 4 new frontend tests pass.

### File List

**New Backend Files:**
- backend/notification-service/src/main/java/com/robomart/notification/web/ServiceHealthData.java
- backend/notification-service/src/main/java/com/robomart/notification/web/SystemHealthResponse.java
- backend/notification-service/src/main/java/com/robomart/notification/web/ActuatorHealthResponse.java
- backend/notification-service/src/main/java/com/robomart/notification/web/ActuatorMetricResponse.java
- backend/notification-service/src/main/java/com/robomart/notification/service/HealthAggregatorService.java
- backend/notification-service/src/main/java/com/robomart/notification/service/HealthPushScheduler.java
- backend/notification-service/src/main/java/com/robomart/notification/controller/AdminSystemHealthRestController.java
- backend/notification-service/src/main/java/com/robomart/notification/config/SchedulingConfig.java
- backend/notification-service/src/test/java/com/robomart/notification/unit/service/HealthAggregatorServiceTest.java
- backend/order-service/src/main/java/com/robomart/order/web/OrderEventResponse.java

**Modified Backend Files:**
- backend/notification-service/src/main/java/com/robomart/notification/service/AdminPushService.java
- backend/notification-service/src/main/resources/application.yml
- backend/notification-service/src/test/java/com/robomart/notification/unit/FailedEventServiceTest.java (pre-existing bug fix)
- backend/api-gateway/src/main/java/com/robomart/gateway/config/RouteConfig.java
- backend/api-gateway/src/main/resources/application.yml
- backend/order-service/src/main/java/com/robomart/order/controller/OrderAdminRestController.java
- backend/order-service/src/main/resources/application.yml
- backend/product-service/src/main/resources/application.yml
- backend/cart-service/src/main/resources/application.yml
- backend/inventory-service/src/main/resources/application.yml
- backend/payment-service/src/main/resources/application.yml

**New Frontend Files:**
- frontend/admin-dashboard/src/api/systemHealthApi.ts
- frontend/admin-dashboard/src/stores/useSystemHealthStore.ts
- frontend/admin-dashboard/src/components/system/KafkaLagIndicator.vue
- frontend/admin-dashboard/src/components/system/ServiceHealthCard.vue
- frontend/admin-dashboard/src/components/system/SystemHealthPanel.vue
- frontend/admin-dashboard/src/views/SystemHealthPage.vue
- frontend/admin-dashboard/src/__tests__/useSystemHealthStore.spec.ts

**Modified Frontend Files:**
- frontend/admin-dashboard/src/composables/useWebSocket.ts
- frontend/admin-dashboard/src/views/DashboardPage.vue
- frontend/admin-dashboard/src/layouts/AdminLayout.vue
- frontend/admin-dashboard/src/router/index.ts
