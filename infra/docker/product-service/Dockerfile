# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Copy Maven wrapper and parent POM for dependency caching
COPY backend/mvnw backend/mvnw
COPY backend/.mvn backend/.mvn
COPY backend/pom.xml backend/pom.xml

# Copy shared module POMs for dependency resolution
COPY backend/common-lib/pom.xml backend/common-lib/pom.xml
COPY backend/security-lib/pom.xml backend/security-lib/pom.xml
COPY backend/proto/pom.xml backend/proto/pom.xml
COPY backend/events/pom.xml backend/events/pom.xml
COPY backend/test-support/pom.xml backend/test-support/pom.xml
COPY backend/product-service/pom.xml backend/product-service/pom.xml
COPY backend/cart-service/pom.xml backend/cart-service/pom.xml
COPY backend/api-gateway/pom.xml backend/api-gateway/pom.xml
COPY backend/order-service/pom.xml backend/order-service/pom.xml
COPY backend/inventory-service/pom.xml backend/inventory-service/pom.xml
COPY backend/payment-service/pom.xml backend/payment-service/pom.xml
COPY backend/notification-service/pom.xml backend/notification-service/pom.xml
COPY backend/e2e-tests/pom.xml backend/e2e-tests/pom.xml

# Download dependencies (cached layer)
WORKDIR /workspace/backend
RUN ./mvnw dependency:go-offline -pl :product-service -am -DskipTests -q

# Copy source
COPY backend/common-lib/src backend/common-lib/src
COPY backend/security-lib/src backend/security-lib/src
COPY backend/proto/src backend/proto/src
COPY backend/events/src backend/events/src
COPY backend/product-service/src backend/product-service/src

# Build JAR (skip tests — tests run in CI before Docker build)
RUN ./mvnw package -pl :product-service -am -DskipTests -DskipITs -q

# Extract the Spring Boot executable JAR (excludes *-plain.jar produced by repackage)
RUN find /workspace/backend/product-service/target -name '*.jar' ! -name '*-plain.jar' -exec cp {} /workspace/app.jar \;

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Security: run as non-root user
RUN addgroup -g 1001 -S robomart && adduser -u 1001 -S robomart -G robomart
USER robomart

# Copy built JAR (resolved to exact path in builder stage)
COPY --from=builder /workspace/app.jar app.jar

# Health check (Spring Boot Actuator)
HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
  CMD wget -qO- http://localhost:8081/actuator/health/liveness || exit 1

EXPOSE 8081
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
