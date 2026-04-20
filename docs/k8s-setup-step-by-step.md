# K8s Setup — Step by Step

Hướng dẫn này dựa trên toàn bộ config thực tế trong project. Thực hiện theo đúng thứ tự.

---

## Prerequisites

```bash
# Cần có sẵn
kubectl   # >= 1.28
helm      # >= 3.14
kustomize # >= 5.4  (hoặc dùng kubectl kustomize)

# Kiểm tra
kubectl version --client
helm version
kustomize version
```

---

## Phase 1 — Cluster

Chọn một trong hai:

**Local (test luồng CI/CD trước):**
```bash
# minikube — cần Docker đang chạy
brew install minikube
minikube start --cpus=6 --memory=12288 --driver=docker
minikube addons enable metrics-server  # bắt buộc cho HPA
```

**Cloud (production):**
```bash
# GKE — ví dụ
gcloud container clusters create robomart \
  --num-nodes=3 \
  --machine-type=e2-standard-4 \
  --region=asia-southeast1
gcloud container clusters get-credentials robomart --region=asia-southeast1
```

Verify:
```bash
kubectl cluster-info
kubectl get nodes
```

---

## Phase 2 — Namespace & Helm repos

```bash
# Tạo namespace trước tất cả
kubectl apply -f infra/k8s/base/namespace.yml

# Thêm Helm repos
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo add confluentinc https://confluentinc.github.io/cp-helm-charts/
helm repo update
```

---

## Phase 3 — Infrastructure services

Deploy theo đúng thứ tự: DB trước, Kafka sau, Keycloak cuối.

### 3.1 — PostgreSQL (5 instances, mỗi service 1 cái)

```bash
# product_db  — user: robomart
helm install product-postgres bitnami/postgresql \
  --namespace robomart \
  --set auth.database=product_db \
  --set auth.username=robomart \
  --set auth.password=<STRONG_PASSWORD> \
  --set primary.persistence.size=10Gi

# order_db — user: robomart
helm install order-postgres bitnami/postgresql \
  --namespace robomart \
  --set auth.database=order_db \
  --set auth.username=robomart \
  --set auth.password=<STRONG_PASSWORD> \
  --set primary.persistence.size=10Gi

# inventory_db — user: robomart
helm install inventory-postgres bitnami/postgresql \
  --namespace robomart \
  --set auth.database=inventory_db \
  --set auth.username=robomart \
  --set auth.password=<STRONG_PASSWORD> \
  --set primary.persistence.size=10Gi

# payment_db — user: robomart
helm install payment-postgres bitnami/postgresql \
  --namespace robomart \
  --set auth.database=payment_db \
  --set auth.username=robomart \
  --set auth.password=<STRONG_PASSWORD> \
  --set primary.persistence.size=10Gi

# notification_db — user: postgres (khác các service còn lại!)
helm install notification-postgres bitnami/postgresql \
  --namespace robomart \
  --set auth.database=notification_db \
  --set auth.username=postgres \
  --set auth.password=<STRONG_PASSWORD> \
  --set primary.persistence.size=5Gi
```

> Dùng **cùng một password mạnh** cho tất cả để dễ quản lý, hoặc khác nhau nếu muốn isolation cao hơn.

### 3.2 — Redis

```bash
helm install redis bitnami/redis \
  --namespace robomart \
  --set auth.enabled=false \
  --set replica.replicaCount=1 \
  --set master.persistence.size=5Gi
```

> `auth.enabled=false` để khớp với config trong `configmap.yml` (không có `SPRING_DATA_REDIS_PASSWORD`). Bật auth sau nếu cần.

### 3.3 — Kafka (KRaft mode, không cần Zookeeper)

```bash
helm install kafka bitnami/kafka \
  --namespace robomart \
  --set kraft.enabled=true \
  --set replicaCount=1 \
  --set listeners.client.protocol=PLAINTEXT \
  --set persistence.size=10Gi
```

### 3.4 — Schema Registry

```bash
# Schema Registry cần Kafka đang chạy
helm install schema-registry bitnami/schema-registry \
  --namespace robomart \
  --set kafka.enabled=false \
  --set externalKafka.brokers=kafka.robomart.svc.cluster.local:9092
```

### 3.5 — Elasticsearch

```bash
helm install elasticsearch bitnami/elasticsearch \
  --namespace robomart \
  --set master.replicaCount=1 \
  --set data.replicaCount=1 \
  --set coordinating.replicaCount=0 \
  --set security.enabled=false \
  --set master.persistence.size=10Gi
```

### 3.6 — Keycloak DB + Keycloak

```bash
# DB riêng cho Keycloak
helm install keycloak-db bitnami/postgresql \
  --namespace robomart \
  --set auth.database=keycloak \
  --set auth.username=keycloak \
  --set auth.password=<STRONG_PASSWORD> \
  --set primary.persistence.size=5Gi

# Keycloak — import realm robomart
helm install keycloak bitnami/keycloak \
  --namespace robomart \
  --set auth.adminUser=admin \
  --set auth.adminPassword=<STRONG_PASSWORD> \
  --set postgresql.enabled=false \
  --set externalDatabase.host=keycloak-db-postgresql.robomart.svc.cluster.local \
  --set externalDatabase.database=keycloak \
  --set externalDatabase.user=keycloak \
  --set externalDatabase.password=<STRONG_PASSWORD>
```

Sau khi Keycloak ready, import realm:
```bash
# Port-forward để truy cập Keycloak admin
kubectl port-forward svc/keycloak -n robomart 8180:80

# Vào http://localhost:8180, login admin
# Realms → Import realm → upload file infra/docker/keycloak/robomart-realm.json
```

### Verify tất cả infra đang chạy

```bash
kubectl get pods -n robomart
# Chờ tất cả STATUS = Running, READY = 1/1 (hoặc 2/2)
```

---

## Phase 4 — Cập nhật ConfigMap và Secrets

### 4.1 — Cập nhật configmap.yml cho đúng địa chỉ K8s

Helm đặt tên service theo pattern `{release-name}-{chart-name}.{namespace}.svc.cluster.local`. Cập nhật [infra/k8s/base/configmap.yml](../infra/k8s/base/configmap.yml):

```yaml
data:
  # Kafka — Helm release: kafka, chart: kafka
  SPRING_KAFKA_BOOTSTRAP_SERVERS: "kafka.robomart.svc.cluster.local:9092"
  KAFKA_BOOTSTRAP_SERVERS: "kafka.robomart.svc.cluster.local:9092"

  # Schema Registry — Helm release: schema-registry
  SPRING_KAFKA_PROPERTIES_SCHEMA_REGISTRY_URL: "http://schema-registry.robomart.svc.cluster.local:8081"
  SCHEMA_REGISTRY_URL: "http://schema-registry.robomart.svc.cluster.local:8081"

  # Redis — Helm release: redis
  SPRING_DATA_REDIS_HOST: "redis-master.robomart.svc.cluster.local"
  SPRING_DATA_REDIS_PORT: "6379"

  # Elasticsearch — Helm release: elasticsearch
  SPRING_ELASTICSEARCH_URIS: "http://elasticsearch.robomart.svc.cluster.local:9200"

  # Keycloak — Helm release: keycloak
  SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI: "http://keycloak.robomart.svc.cluster.local/realms/robomart"
  KEYCLOAK_JWK_SET_URI: "http://keycloak.robomart.svc.cluster.local/realms/robomart/protocol/openid-connect/certs"

  # gRPC (địa chỉ K8s service — giữ nguyên, đã đúng)
  GRPC_CLIENT_INVENTORY_SERVICE_ADDRESS: "static://inventory-service:9094"
  GRPC_CLIENT_PAYMENT_SERVICE_ADDRESS: "static://payment-service:9095"

  # Service URLs (giữ nguyên — đã đúng với K8s DNS)
  ORDER_SERVICE_URL: "http://order-service:8083"
  PRODUCT_SERVICE_URL: "http://product-service:8081"
  # ... các URL còn lại giữ nguyên
```

> Kiểm tra tên service thật bằng: `kubectl get svc -n robomart`

Apply sau khi sửa:
```bash
kubectl apply -f infra/k8s/base/configmap.yml
```

### 4.2 — Apply Secrets

```bash
# Tạo file tạm từ template, KHÔNG commit file này
cp infra/k8s/base/secrets-template.yml /tmp/robomart-secrets.yml
```

Mở `/tmp/robomart-secrets.yml`, sửa:

- Thay tất cả `REPLACE_ME` bằng password tương ứng đã dùng ở Phase 3
- Thay hostname PostgreSQL theo đúng K8s DNS. Ví dụ:
  - `product-postgres-postgresql.robomart.svc.cluster.local:5432`
  - `order-postgres-postgresql.robomart.svc.cluster.local:5432`
  - `inventory-postgres-postgresql.robomart.svc.cluster.local:5432`
  - `payment-postgres-postgresql.robomart.svc.cluster.local:5432`
  - `notification-postgres-postgresql.robomart.svc.cluster.local:5432`

```bash
kubectl apply -f /tmp/robomart-secrets.yml -n robomart
rm /tmp/robomart-secrets.yml   # xóa ngay
```

Verify secrets:
```bash
kubectl get secrets -n robomart
# product-db-secret, order-db-secret, inventory-db-secret, payment-db-secret, notification-db-secret
```

---

## Phase 5 — ArgoCD

### 5.1 — Cài ArgoCD

```bash
kubectl create namespace argocd
kubectl apply -n argocd \
  -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml

# Chờ ready
kubectl wait --for=condition=available deployment/argocd-server \
  -n argocd --timeout=300s
```

### 5.2 — Lấy initial password và đổi ngay

```bash
# Lấy password
ARGOCD_PASS=$(kubectl get secret argocd-initial-admin-secret \
  -n argocd -o jsonpath="{.data.password}" | base64 -d)
echo $ARGOCD_PASS

# Port-forward UI
kubectl port-forward svc/argocd-server -n argocd 8090:443 &

# Đăng nhập bằng CLI
argocd login localhost:8090 \
  --username admin \
  --password $ARGOCD_PASS \
  --insecure

# Đổi password ngay
argocd account update-password
```

### 5.3 — Kết nối repo (nếu private)

```bash
argocd repo add https://github.com/tuanpm2026/robo-mart \
  --username tuanpm2026 \
  --password <GITHUB_PAT>   # Personal Access Token, scope: read:repo
```

### 5.4 — Apply ArgoCD Application

```bash
kubectl apply -f infra/k8s/argocd/robomart-app.yaml
```

Verify:
```bash
argocd app list
# NAME                  CLUSTER    NAMESPACE  PROJECT  STATUS  HEALTH
# robomart-production   in-cluster robomart   default  Synced  Healthy
```

---

## Phase 6 — Verify toàn bộ stack

```bash
# Tất cả pods phải Running
kubectl get pods -n robomart -w

# Tất cả deployments đủ replicas
kubectl get deployments -n robomart

# HPA đang đọc được metrics
kubectl get hpa -n robomart

# External IP của api-gateway (LoadBalancer)
kubectl get svc api-gateway -n robomart
```

Truy cập app:
```bash
GATEWAY_IP=$(kubectl get svc api-gateway -n robomart \
  -o jsonpath='{.status.loadBalancer.ingress[0].ip}')

# Health check
curl http://${GATEWAY_IP}:8080/actuator/health
```

---

## Phase 7 — Trigger CD lần đầu

Sau khi cluster đã chạy, push bất kỳ thay đổi backend lên `main`:

```bash
git commit --allow-empty -m "chore: trigger initial CD deploy"
git push
```

Theo dõi:
```bash
# GitHub Actions build & push images + commit tag
gh run watch

# ArgoCD phát hiện commit mới và sync (mặc định poll 3 phút)
argocd app get robomart-production
argocd app sync robomart-production   # force sync ngay nếu không muốn chờ
```

---

## Thứ tự dependency khi deploy

```
Namespace
    │
    ├── PostgreSQL ×5 ──────────────────────┐
    ├── Redis ───────────────────────────┐  │
    ├── Kafka ──────────────────────┐   │  │
    │       └── Schema Registry ──┐ │  │  │
    └── Keycloak DB → Keycloak ─┐ │ │  │  │
                                │ │ │  │  │
    ConfigMap + Secrets ←───────┘ │ │  │  │
                                  │ │  │  │
    Application services: ────────┘ │  │  │
    cart-service          (Redis, Kafka)   │
    product-service       (Redis, Kafka, ES, product-postgres)
    order-service         (Kafka, order-postgres)
    inventory-service     (Redis, Kafka, inventory-postgres)
    payment-service       (Kafka, payment-postgres)
    notification-service  (Kafka, notification-postgres)
    api-gateway           (Redis, Keycloak, tất cả services trên)
```

ArgoCD tự deploy app services sau khi infra ready (sync từ git).

---

## Troubleshooting nhanh

```bash
# Pod không start — xem lỗi
kubectl describe pod <pod-name> -n robomart
kubectl logs <pod-name> -n robomart --previous

# Service không resolve — test DNS trong cluster
kubectl run -it --rm debug --image=busybox --restart=Never -n robomart -- \
  nslookup kafka.robomart.svc.cluster.local

# ArgoCD out of sync
argocd app diff robomart-production
argocd app sync robomart-production

# HPA không scale — metrics server chưa chạy
kubectl top pods -n robomart
kubectl top nodes
```
