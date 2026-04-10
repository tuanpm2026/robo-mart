# ---- Stage 1: Build all modules ----
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /workspace

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

COPY mvnw mvnw.cmd ./
COPY .mvn .mvn/
RUN chmod +x mvnw

# Copy POMs first for layer caching
COPY pom.xml .
COPY common-lib/pom.xml common-lib/
COPY security-lib/pom.xml security-lib/
COPY proto/pom.xml proto/
COPY events/pom.xml events/
COPY test-support/pom.xml test-support/
COPY product-service/pom.xml product-service/
COPY cart-service/pom.xml cart-service/
COPY api-gateway/pom.xml api-gateway/
COPY order-service/pom.xml order-service/
COPY inventory-service/pom.xml inventory-service/
COPY payment-service/pom.xml payment-service/
COPY notification-service/pom.xml notification-service/

# Copy full source and build
COPY . .
RUN ./mvnw clean package -DskipTests --no-transfer-progress

# ---- api-gateway ----
FROM eclipse-temurin:21-jre-jammy AS api-gateway
WORKDIR /app
COPY --from=builder /workspace/api-gateway/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

# ---- product-service ----
FROM eclipse-temurin:21-jre-jammy AS product-service
WORKDIR /app
COPY --from=builder /workspace/product-service/target/*.jar app.jar
RUN mkdir -p /tmp/robomart-images
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]

# ---- cart-service ----
FROM eclipse-temurin:21-jre-jammy AS cart-service
WORKDIR /app
COPY --from=builder /workspace/cart-service/target/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]

# ---- order-service ----
FROM eclipse-temurin:21-jre-jammy AS order-service
WORKDIR /app
COPY --from=builder /workspace/order-service/target/*.jar app.jar
EXPOSE 8083 9093
ENTRYPOINT ["java", "-jar", "app.jar"]

# ---- inventory-service ----
FROM eclipse-temurin:21-jre-jammy AS inventory-service
WORKDIR /app
COPY --from=builder /workspace/inventory-service/target/*.jar app.jar
EXPOSE 8084 9094
ENTRYPOINT ["java", "-jar", "app.jar"]

# ---- payment-service ----
FROM eclipse-temurin:21-jre-jammy AS payment-service
WORKDIR /app
COPY --from=builder /workspace/payment-service/target/*.jar app.jar
EXPOSE 8086 9095
ENTRYPOINT ["java", "-jar", "app.jar"]

# ---- notification-service ----
FROM eclipse-temurin:21-jre-jammy AS notification-service
WORKDIR /app
COPY --from=builder /workspace/notification-service/target/*.jar app.jar
EXPOSE 8087
ENTRYPOINT ["java", "-jar", "app.jar"]
