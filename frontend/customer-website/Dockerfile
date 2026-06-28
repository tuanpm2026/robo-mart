FROM node:22-alpine AS builder
WORKDIR /app

# Copy workspace root (needed for npm workspaces)
COPY package.json package-lock.json ./
COPY shared/ shared/
COPY customer-website/package.json customer-website/
COPY admin-dashboard/package.json admin-dashboard/

RUN npm ci

COPY customer-website/ customer-website/

WORKDIR /app/customer-website
ARG VITE_API_URL=http://localhost:8080
ARG VITE_KEYCLOAK_URL=http://localhost:8180
ARG VITE_KEYCLOAK_REALM=robomart
ARG VITE_KEYCLOAK_CLIENT_ID=robo-mart-frontend
ENV VITE_API_URL=$VITE_API_URL
ENV VITE_KEYCLOAK_URL=$VITE_KEYCLOAK_URL
ENV VITE_KEYCLOAK_REALM=$VITE_KEYCLOAK_REALM
ENV VITE_KEYCLOAK_CLIENT_ID=$VITE_KEYCLOAK_CLIENT_ID
RUN npm run build-only

# nginx-unprivileged runs as non-root uid 101 and listens on an unprivileged
# port (8080) — satisfies Trivy DS-0002 (non-root image) and KSV-0117 (no
# privileged-port bind). COPY runs as root so the static assets/config land with
# the right ownership; the final USER is 101.
FROM nginxinc/nginx-unprivileged:alpine
USER root
COPY --from=builder /app/customer-website/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
USER 101
EXPOSE 8080
