# Patched community source for isolated/local development; production uses managed S3-compatible storage.
FROM golang:1.26-alpine@sha256:a2012b4a1d1f9f7e9f44e0d39b805a901e856b1dd80b4e6ee4338db547050308 AS builder
ENV CGO_ENABLED=0 GOMAXPROCS=2 GOTOOLCHAIN=local
ADD https://codeload.github.com/minio/minio/tar.gz/9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a /tmp/minio.tar.gz
RUN echo '45521908307306e925c98d629e1c17d78c8b72b6ee242b1bfb1409f7d8ee5841  /tmp/minio.tar.gz' | sha256sum -c - \
    && mkdir /source && tar -xzf /tmp/minio.tar.gz --strip-components=1 -C /source
WORKDIR /source
RUN go mod download
RUN go build -p 2 -trimpath -ldflags='-s -w' -o /minio .

FROM alpine:3.23@sha256:ff7f0c727dd796aa36787bfcd9a64bf35a6ce72a73451b3359fde849b6703d63
LABEL org.opencontainers.image.source="https://github.com/minio/minio" \
      org.opencontainers.image.revision="9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a" \
      org.opencontainers.image.version="RELEASE.2025-10-15T17-29-55Z" \
      org.opencontainers.image.licenses="AGPL-3.0-only"
RUN apk add --no-cache ca-certificates curl \
    && addgroup -g 1001 robomart && adduser -D -u 1001 -G robomart robomart \
    && mkdir /data && chown 1001:1001 /data
COPY --from=builder /minio /usr/local/bin/minio
COPY --from=builder /source/LICENSE /usr/share/licenses/minio/LICENSE
USER 1001:1001
ENV GOMEMLIMIT=256MiB GOMAXPROCS=2
EXPOSE 9000 9001
HEALTHCHECK --interval=10s --timeout=5s --retries=30 CMD curl -fsS http://localhost:9000/minio/health/ready || exit 1
ENTRYPOINT ["minio"]
CMD ["server", "/data", "--console-address", ":9001"]
