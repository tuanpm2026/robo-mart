# Story 7.1: Implement WebSocket Real-Time Event Feed

Status: done

## Story

As an admin,
I want to see live order events and inventory alerts streaming in real-time on my dashboard,
so that I can monitor operations as they happen without refreshing.

## Acceptance Criteria

1. **Given** Notification Service with WebSocketConfig **When** configured **Then** Spring WebSocket + STOMP protocol is enabled with SockJS fallback; JWT is validated on STOMP CONNECT frame (not per-message).

2. **Given** an `order.order.status-changed` Kafka event consumed by Notification Service **When** processed **Then** it is pushed to Admin Dashboard via WebSocket topic `/topic/orders` with: orderId, status, customer (userId), total, timestamp. (FR46)

3. **Given** an `inventory.stock.low-alert` Kafka event consumed by Notification Service **When** processed **Then** it is pushed to Admin Dashboard via WebSocket topic `/topic/inventory-alerts` with: productName, currentStock, threshold. (FR47)

4. **Given** the Admin Dashboard Live Feed panel **When** WebSocket events arrive **Then** new events slide in at the top with subtle CSS animation; auto-scroll is active when not paused; each event shows: type icon, description, timestamp ("2s ago"). (UX-DR18)

5. **Given** the Live Feed panel **When** I click "Pause" **Then** auto-scroll stops and a "N new events" badge appears; clicking "Resume" scrolls to newest and resumes auto-scroll. (UX-DR18)

6. **Given** ConnectionStatus component in Admin header **When** WebSocket is connected **Then** a green 8px dot indicator is shown silently. If disconnected >5s: yellow dot + "Reconnecting..." label + toast. On reconnect: green dot + brief "Connection restored" toast. (UX-DR3)

7. **Given** the API Gateway **When** a WebSocket upgrade request arrives at `/ws/**` **Then** it is proxied to the Notification Service (`http://notification-service:8087`).

## Tasks / Subtasks

### Backend — Notification Service

- [x] **Task 1: Add WebSocket dependency to notification-service pom.xml** (AC: 1)
  - [x] Add `spring-boot-starter-websocket` dependency
  - [x] Verify no version conflict (Spring Boot 4.0.4 manages it)

- [x] **Task 2: Create `WebSocketConfig.java`** (AC: 1)
  - [x] Enable `@EnableWebSocketMessageBroker`
  - [x] Configure in-memory simple broker for `/topic`
  - [x] Set application destination prefix: `/app`
  - [x] Register SockJS endpoint at `/ws` (allow all origins for dev: `"*"`)
  - [x] Register `JwtStompInterceptor` as inbound channel interceptor

- [x] **Task 3: Create `JwtStompInterceptor.java`** (AC: 1)
  - [x] Implement `ChannelInterceptor`
  - [x] On `StompCommand.CONNECT`: extract `Authorization` header from `StompHeaderAccessor`
  - [x] Validate JWT using `JwtDecoder` bean (NimbusJwtDecoder via spring-security-oauth2-jose)
  - [x] Throw `MessageDeliveryException` on missing/invalid JWT
  - [x] Allow all other commands to pass through without re-validation

- [x] **Task 4: Create `AdminPushService.java`** (AC: 2, 3)
  - [x] Inject `SimpMessagingTemplate`
  - [x] Method `pushOrderEvent(OrderStatusChangedEvent event)`: fetch customer (userId from event), send to `/topic/orders`
  - [x] Method `pushInventoryAlert(StockLowAlertEvent event)`: resolve productName via `ProductServiceClient`, send to `/topic/inventory-alerts`
  - [x] Payload for orders: `{ eventType, orderId, status, userId, total, timestamp }`
  - [x] Payload for inventory: `{ eventType, productId, productName, currentStock, threshold, timestamp }`
  - [x] Use Java records for payloads (Jackson 3.x serializes automatically)
  - [x] Log push at DEBUG level; swallow exceptions to protect Kafka consumers

- [x] **Task 5: Modify `OrderEventConsumer.java`** (AC: 2)
  - [x] Inject `AdminPushService`
  - [x] After existing notification logic, call `adminPushService.pushOrderEvent(event)`
  - [x] Push is fail-safe — AdminPushService wraps in try-catch, logs WARN

- [x] **Task 6: Modify `InventoryAlertConsumer.java`** (AC: 3)
  - [x] Inject `AdminPushService`
  - [x] After existing notification logic, call `adminPushService.pushInventoryAlert(event)`
  - [x] Push is fail-safe — AdminPushService wraps in try-catch, logs WARN

- [x] **Task 7: Backend unit tests** (AC: 1, 2, 3)
  - [x] `JwtStompInterceptorTest`: 5 tests — valid JWT, missing header, non-Bearer, invalid JWT, non-CONNECT command
  - [x] `AdminPushServiceTest`: 4 tests — order push, inventory push, order service failure resilience, product service failure resilience
  - [x] Fixed existing `OrderEventConsumerTest` and `InventoryAlertConsumerTest` to mock new `AdminPushService` dep

### Backend — API Gateway

- [x] **Task 8: Add WebSocket route in `RouteConfig.java`** (AC: 7)
  - [x] Added `notificationServiceUri` field with `@Value("${gateway.services.notification-service:http://localhost:8087}")`
  - [x] Added `notification-websocket` route as first route: path `/ws/**` → notification-service
  - [x] Spring Cloud Gateway WebFlux proxies WebSocket upgrades natively

### Frontend — Admin Dashboard

- [x] **Task 9: Install WebSocket dependencies** (AC: 4, 5, 6)
  - [x] Installed `@stomp/stompjs`, `sockjs-client`, `@types/sockjs-client`

- [x] **Task 10: Create `src/api/websocketClient.ts`** (AC: 4, 5, 6)
  - [x] `createWebSocketClient(token)` factory using `@stomp/stompjs` `Client` class
  - [x] SockJS transport to `${VITE_API_URL}/ws` (proxied via API Gateway)
  - [x] `reconnectDelay: 5000`, `Authorization: Bearer ${token}` connect headers

- [x] **Task 11: Create `src/stores/useWebSocketStore.ts`** (AC: 4, 5, 6)
  - [x] Pinia Composition API style
  - [x] `connectionStatus`, `events[]` (capped 100, newest first), `isPaused`, `newEventCount`
  - [x] `addEvent()`, `pause()`, `resume()`, `setConnectionStatus()` actions

- [x] **Task 12: Create `src/composables/useWebSocket.ts`** (AC: 4, 5, 6)
  - [x] `connect()` / `disconnect()` using `createWebSocketClient`
  - [x] Subscribes to `/topic/orders` and `/topic/inventory-alerts`
  - [x] 5s disconnect timer sets `'reconnecting'` status
  - [x] Shows "Connection restored" toast on reconnect

- [x] **Task 13: Create `src/components/dashboard/LiveOrderFeed.vue`** (AC: 4, 5)
  - [x] Events list with `v-for`, type icons (🛒/⚠️), relative timestamps
  - [x] Auto-scroll to top when not paused
  - [x] Pause/Resume button + new-event badge
  - [x] `<TransitionGroup>` slide-in animation
  - [x] Empty state while waiting

- [x] **Task 14: Create `src/components/common/ConnectionStatus.vue`** (AC: 6)
  - [x] 8px dot: green (connected), amber pulsing (reconnecting), red (disconnected)
  - [x] Labels for reconnecting and disconnected states
  - [x] `aria-live="polite"` accessibility

- [x] **Task 15: Update `DashboardPage.vue`** (AC: 4, 5)
  - [x] Replaced `AdminDataTableDemo` with `LiveOrderFeed`
  - [x] `onMounted` → `connect()`, `onUnmounted` → `disconnect()`

- [x] **Task 16: Update `AdminLayout.vue`** (AC: 6)
  - [x] Added `ConnectionStatus` to topbar actions area (first item)

- [x] **Task 17: Frontend unit tests** (AC: 4, 5, 6)
  - [x] `useWebSocketStore.spec.ts`: 6 tests — prepend, cap-100, pause badge, resume reset, no-badge when active, setConnectionStatus
  - [x] `ConnectionStatus.spec.ts`: 3 tests — green/yellow/red dot rendering, labels

## Dev Notes

### Architecture Overview

This story adds **WebSocket/STOMP real-time push** from Notification Service to Admin Dashboard:

```
Kafka → OrderEventConsumer → AdminPushService → SimpMessagingTemplate → /topic/orders
Kafka → InventoryAlertConsumer → AdminPushService → SimpMessagingTemplate → /topic/inventory-alerts

Admin Dashboard (SockJS + STOMP.js) ← API Gateway (/ws proxy) ← Notification Service (:8087/ws)
```

WebSocket flow is **server push only** — no client-to-server messaging needed for this story.

### Backend Package Structure

All new files go in Notification Service (`notification-service/`):

```
src/main/java/com/robomart/notification/
  config/
    WebSocketConfig.java          ← NEW
    JwtStompInterceptor.java      ← NEW
    KafkaConsumerConfig.java      ← existing (do NOT touch)
    KafkaDlqConfig.java           ← existing (do NOT touch)
  service/
    AdminPushService.java         ← NEW
    NotificationService.java      ← existing (do NOT touch unless needed)
  event/consumer/
    OrderEventConsumer.java       ← MODIFY (add push call)
    InventoryAlertConsumer.java   ← MODIFY (add push call)
    CartExpiryConsumer.java       ← do NOT touch
    DlqConsumer.java              ← do NOT touch
```

### WebSocketConfig Pattern (Spring Boot 4)

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")  // restrict to actual origins in prod
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(jwtStompInterceptor());
    }

    @Bean
    public JwtStompInterceptor jwtStompInterceptor() {
        return new JwtStompInterceptor(jwtDecoder);
    }
}
```

### JWT Validation on STOMP CONNECT

```java
@Component
public class JwtStompInterceptor implements ChannelInterceptor {
    private final JwtDecoder jwtDecoder;  // inject from security-lib bean

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor
            .getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                throw new MessageDeliveryException(message, "Missing Authorization header");
            }
            try {
                Jwt jwt = jwtDecoder.decode(authHeader.substring(7));
                // Optionally set SecurityContext here
            } catch (JwtException e) {
                throw new MessageDeliveryException(message, "Invalid JWT: " + e.getMessage());
            }
        }
        return message;
    }
}
```

**Critical**: `JwtDecoder` is already configured by `security-lib` as a Spring bean — inject it directly, do NOT create a new one.

### AdminPushService Payload — Use Records

Jackson 3.x serializes records correctly. Use simple records for payloads:

```java
public record OrderEventPayload(
    String eventType,
    String orderId,
    String status,
    String userId,
    String timestamp
) {}

public record InventoryAlertPayload(
    String eventType,
    String productId,
    String productName,
    int currentStock,
    int threshold,
    String timestamp
) {}
```

`SimpMessagingTemplate.convertAndSend("/topic/orders", payload)` — Jackson serializes automatically.

### API Gateway WebSocket Route

In `RouteConfig.java`, add routes **before** existing catch-all routes:

```java
.route("websocket-ws", r -> r
    .path("/ws", "/ws/**")
    .uri("http://localhost:8087"))  // or "lb://notification-service" if using service discovery
```

Spring Cloud Gateway WebFlux proxies WebSocket upgrades natively. No special filter needed.

### Frontend SockJS + STOMP.js Pattern

Use `@stomp/stompjs` v7.x `Client` class (NOT the legacy `Stomp.over()` API):

```typescript
import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'

const client = new Client({
  webSocketFactory: () => new SockJS('http://localhost:8080/ws'),
  connectHeaders: {
    Authorization: `Bearer ${token}`
  },
  reconnectDelay: 5000,
  onConnect: () => {
    store.setConnectionStatus('connected')
    client.subscribe('/topic/orders', (message) => {
      const payload = JSON.parse(message.body)
      store.addEvent({ ... })
    })
    client.subscribe('/topic/inventory-alerts', (message) => {
      const payload = JSON.parse(message.body)
      store.addEvent({ ... })
    })
  },
  onDisconnect: () => store.setConnectionStatus('disconnected'),
  onStompError: (frame) => console.error('STOMP error', frame)
})

client.activate()
```

**Important**: SockJS connection URL must be `http://` not `ws://` — SockJS negotiates transport.

### Reconnect Strategy (5s Disconnect Detection)

UX requirement: show "Reconnecting..." only after 5s, not immediately. Implement with a timer:

```typescript
let reconnectTimer: ReturnType<typeof setTimeout> | null = null

onDisconnect: () => {
  reconnectTimer = setTimeout(() => {
    store.setConnectionStatus('reconnecting')
  }, 5000)
},
onConnect: () => {
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  const wasReconnecting = store.connectionStatus !== 'disconnected'
  store.setConnectionStatus('connected')
  if (wasReconnecting) {
    toast.add({ severity: 'success', summary: 'Connection restored', life: 3000 })
  }
  // subscribe to topics here
}
```

### Existing Code to Reuse

- `ProductServiceClient.java` — already has `getProductName(productId)` with in-memory cache. Use this in `AdminPushService` to resolve product names for inventory alerts.
- `OrderServiceClient.java` — already fetches order details including `totalAmount`. Use if needed for order total in push payload.
- `NotificationType` enum — do NOT add new entries (push is not a logged notification type)
- `NotificationLog` — do NOT log WebSocket pushes (they are ephemeral, not stored)
- `useAdminAuthStore.ts` — already has `token` getter. Use this in `useWebSocket.ts` to get the JWT.
- `useToast()` from PrimeVue — already used in `OrdersPage.vue`. Follow same pattern.
- `AdminLayout.vue` topbar section — add `ConnectionStatus` inside the existing topbar right section.

### Critical Pitfalls from Previous Stories

1. **`@MockBean` is deprecated** — use `@MockitoBean` from `org.springframework.test.context.bean.override.mockito`.

2. **Jackson 3.x package** — if you need any Jackson annotations in payload records, use `com.fasterxml.jackson.annotation` (annotations unchanged) but databind is `tools.jackson.databind`. For simple records with primitives/strings, no annotation needed.

3. **Spring Boot 4 WebTestClient** — not auto-configured. For integration tests that test WebSocket, use `WebSocketStompClient` from `spring-websocket-test` (Spring's test support). Or test `AdminPushService` in isolation with mocked `SimpMessagingTemplate`.

4. **Multiple KafkaConsumerFactory beans** — `KafkaDlqConfig` and `KafkaConsumerConfig` already use `@Qualifier`. Do NOT add another `ConsumerFactory` bean without `@Qualifier`.

5. **DLQ interceptor must NOT apply to WebSocket** — the `DefaultErrorHandler` with `DeadLetterPublishingRecoverer` is only on Kafka consumers. `AdminPushService` failures should be silent (log + continue), never thrown up to break the Kafka consumer.

6. **SockJS negotiation** — SockJS tries WebSocket first, then HTTP streaming, then long-polling. The API Gateway must support all three transport types. Spring Cloud Gateway WebFlux handles WebSocket upgrades natively — this works by default.

7. **Frontend token expiry** — the STOMP session is authenticated once on CONNECT. If the JWT expires mid-session, the connection stays alive (no per-message re-auth). Token refresh/reconnect on expiry is out of scope for this story.

### Notification Service Port

Notification Service: **port 8087** (`application.yml`, `server.port: 8087`).
API Gateway proxies `/ws/**` → `http://localhost:8087` (local dev).

### Kafka Consumer Context

The existing `OrderEventConsumer` consumes `order.order.status-changed` topic using `OrderStatusChangedEvent` (Avro). Key fields available: `orderId` (String), `newStatus` (String), `previousStatus` (String), `eventId`, `timestamp` (long millis). Customer/total must be fetched from `OrderServiceClient` if needed in payload.

`InventoryAlertConsumer` consumes `inventory.stock.low-alert` using `StockLowAlertEvent`. Key fields: `productId` (String), `currentQuantity` (int), `threshold` (int). Product name resolved via `ProductServiceClient`.

### Testing Standards

**Backend unit test example:**
```java
@ExtendWith(MockitoExtension.class)
class AdminPushServiceTest {
    @Mock SimpMessagingTemplate messagingTemplate;
    @Mock ProductServiceClient productServiceClient;
    @InjectMocks AdminPushService adminPushService;

    @Test
    void pushInventoryAlert_sendsToCorrectTopic() {
        // Arrange
        StockLowAlertEvent event = StockLowAlertEvent.newBuilder()...build();
        when(productServiceClient.getProductName("prod-1")).thenReturn("Wireless Headphone X");

        // Act
        adminPushService.pushInventoryAlert(event);

        // Assert
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/inventory-alerts"), captor.capture());
        // assert payload fields
    }
}
```

**Frontend unit test example (Vitest):**
```typescript
describe('useWebSocketStore', () => {
  it('caps events at 100', () => {
    const store = useWebSocketStore()
    for (let i = 0; i < 110; i++) {
      store.addEvent({ id: String(i), type: 'ORDER', description: 'test', raw: {}, timestamp: new Date() })
    }
    expect(store.events.length).toBe(100)
  })
})
```

### Project Structure Notes

- All new backend files follow package `com.robomart.notification.*`
- All new frontend files follow existing `src/` structure in `admin-dashboard`
- No new Avro schemas needed — this story only consumes existing events
- No new database migrations — WebSocket pushes are not persisted
- No new Kafka topics — this story uses existing `order.order.status-changed` and `inventory.stock.low-alert`

### References

- Architecture: WebSocket stack — `[architecture.md#Communication Patterns]`
- Architecture: Admin dashboard file structure — `[architecture.md#Frontend Directory Structure]`
- Architecture: JWT propagation — `[architecture.md#JWT Propagation Strategy]`
- Architecture: Kafka topic mapping — `[architecture.md#Kafka Topic → Producer/Consumer Mapping]`
- UX: ConnectionStatus component — `[ux-design-specification.md#ConnectionStatus]`
- UX: Live Feed panel — `[ux-design-specification.md#Experience Mechanics]`
- Story 6.3 (DLQ): `[6-3-implement-dead-letter-queue-for-failed-events.md]` — KafkaDlqConfig patterns, @Qualifier usage, DLQ consumer structure
- Story 6.1: Notification Service core patterns established
- `[CLAUDE.md]`: Spring Boot 4 / Jackson 3.x gotchas, service ports

### Review Findings

- [x] [Review][Patch] AC6: Missing "Reconnecting..." toast when status transitions to 'reconnecting' after >5s disconnect [`frontend/admin-dashboard/src/composables/useWebSocket.ts:79`]
- [x] [Review][Patch] AC5: Resume does not immediately scroll to newest — watch fires only on next event, not on isPaused change [`frontend/admin-dashboard/src/components/dashboard/LiveOrderFeed.vue:24`]
- [x] [Review][Defer] Token expiry mid-session not handled — reconnect reuses original (potentially expired) JWT [`frontend/admin-dashboard/src/api/websocketClient.ts`] — deferred, explicit out-of-scope per story notes

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

- Fixed: `event.getTimestamp()` returns `java.time.Instant` (Avro logical type), not `long` — changed `Instant.ofEpochMilli(...)` call to direct `.toString()`
- Fixed: Avro `getOrderId()`/`getNewStatus()` return `String`, not `Utf8` — updated test mocks accordingly
- Fixed: `OrderDetailDto.id` is `Long` (not String) — used `null` in test constructor
- Fixed: Existing `OrderEventConsumerTest` and `InventoryAlertConsumerTest` needed `@Mock AdminPushService` after constructor change

### Completion Notes List

- Implemented Spring WebSocket + STOMP with SockJS fallback on Notification Service (port 8087)
- JWT validation on STOMP CONNECT via `JwtStompInterceptor` using `NimbusJwtDecoder` (spring-security-oauth2-jose, no Spring Security HTTP auto-config)
- `AdminPushService` pushes order status changes to `/topic/orders` and inventory alerts to `/topic/inventory-alerts`; wraps all operations in try-catch to protect Kafka consumers
- Added WebSocket proxy route to API Gateway (`/ws/**` → notification-service:8087)
- Frontend: SockJS + STOMP.js v7.x `Client` API; Pinia `useWebSocketStore` with events capped at 100; `useWebSocket` composable with 5s reconnect detection; `LiveOrderFeed` with TransitionGroup animation; `ConnectionStatus` dot indicator in topbar
- 18 new tests: 5 JwtStompInterceptorTest + 4 AdminPushServiceTest (backend) + 6 useWebSocketStore.spec + 3 ConnectionStatus.spec (frontend)
- All 27 backend unit tests pass; all 64 frontend tests pass (1 pre-existing CommandPalette failure unrelated)

### File List

backend/notification-service/pom.xml
backend/notification-service/src/main/resources/application.yml
backend/notification-service/src/main/java/com/robomart/notification/config/WebSocketConfig.java
backend/notification-service/src/main/java/com/robomart/notification/config/JwtStompInterceptor.java
backend/notification-service/src/main/java/com/robomart/notification/service/AdminPushService.java
backend/notification-service/src/main/java/com/robomart/notification/event/OrderEventConsumer.java
backend/notification-service/src/main/java/com/robomart/notification/event/InventoryAlertConsumer.java
backend/notification-service/src/test/java/com/robomart/notification/unit/JwtStompInterceptorTest.java
backend/notification-service/src/test/java/com/robomart/notification/unit/AdminPushServiceTest.java
backend/notification-service/src/test/java/com/robomart/notification/unit/OrderEventConsumerTest.java
backend/notification-service/src/test/java/com/robomart/notification/unit/InventoryAlertConsumerTest.java
backend/api-gateway/src/main/java/com/robomart/gateway/config/RouteConfig.java
frontend/admin-dashboard/package.json
frontend/admin-dashboard/src/api/websocketClient.ts
frontend/admin-dashboard/src/stores/useWebSocketStore.ts
frontend/admin-dashboard/src/composables/useWebSocket.ts
frontend/admin-dashboard/src/components/dashboard/LiveOrderFeed.vue
frontend/admin-dashboard/src/components/common/ConnectionStatus.vue
frontend/admin-dashboard/src/views/DashboardPage.vue
frontend/admin-dashboard/src/layouts/AdminLayout.vue
frontend/admin-dashboard/src/__tests__/useWebSocketStore.spec.ts
frontend/admin-dashboard/src/__tests__/ConnectionStatus.spec.ts
