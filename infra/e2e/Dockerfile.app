FROM eclipse-temurin:25-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r -g 1001 robomart && useradd -r -u 1001 -g robomart robomart \
    && mkdir -p /app /tmp/robomart-images && chown -R robomart:robomart /app /tmp/robomart-images
ARG SERVICE
WORKDIR /app
COPY --chown=robomart:robomart backend/${SERVICE}/target/${SERVICE}-0.0.1-SNAPSHOT.jar app.jar
USER robomart
ENTRYPOINT ["java", "-jar", "app.jar"]
