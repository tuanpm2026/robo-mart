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
ENV VITE_API_URL=$VITE_API_URL
RUN npm run build-only

FROM nginx:alpine
COPY --from=builder /app/customer-website/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
