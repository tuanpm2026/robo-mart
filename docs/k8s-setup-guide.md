# K8s Setup Guide — Robo-Mart

## Hiện trạng trong project

Project đã có sẵn toàn bộ K8s manifests, **chưa thiếu file nào** để deploy. Cấu trúc:

```
infra/k8s/
├── base/
│   ├── namespace.yml        # Namespace: robomart
│   ├── configmap.yml        # Shared env vars (Kafka, Redis, ES, Keycloak, gRPC addresses...)
│   └── secrets-template.yml # Template điền DB password — KHÔNG commit bản thật
└── services/
    ├── api-gateway/         # deployment.yml · service.yml (LoadBalancer) · hpa.yml
    ├── product-service/     # deployment.yml · service.yml (ClusterIP) · hpa.yml
    ├── cart-service/
    ├── order-service/       # có thêm gRPC port 9093
    ├── inventory-service/   # có thêm gRPC port 9094
    ├── payment-service/     # có thêm gRPC port 9095
    └── notification-service/
```

**Điều còn thiếu** để luồng CD chạy được:

1. Cluster K8s thật (bên dưới hướng dẫn chọn loại nào)
2. Infrastructure services chạy **bên trong cluster** (PostgreSQL, Kafka, Redis, Elasticsearch, Keycloak) — hiện K8s manifests chỉ có application services, chưa có infra services
3. Secret `KUBECONFIG` được set trong GitHub repo
4. Image registry path đúng trong deployment files

---

## Bước 1 — Chọn cluster

### Option A: Môi trường local / dev (minikube hoặc kind)

Phù hợp để test luồng CI/CD trước khi lên cloud.

```bash
# minikube
brew install minikube
minikube start --cpus=4 --memory=8192 --driver=docker

# hoặc kind
brew install kind
kind create cluster --name robomart
```

### Option B: Cloud managed (khuyến nghị cho production)

| Provider | Lệnh tạo cluster |
|---|---|
| GKE (Google) | `gcloud container clusters create robomart --num-nodes=3 --machine-type=e2-standard-2` |
| EKS (AWS) | `eksctl create cluster --name robomart --nodegroup-name workers --node-type t3.medium --nodes 3` |
| AKS (Azure) | `az aks create --resource-group robomart-rg --name robomart --node-count 3 --node-vm-size Standard_B2s` |

---

## Bước 2 — Deploy infrastructure services vào cluster

K8s manifests hiện tại **chỉ có application services**. Trước khi deploy app, cần infra services chạy trong cluster. Có 2 cách:

### Option A: Dùng Helm charts (khuyến nghị)

```bash
# Thêm Helm repos
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo add confluentinc https://confluentinc.github.io/cp-helm-charts/
helm repo update

# Namespace trước
kubectl apply -f infra/k8s/base/namespace.yml

# PostgreSQL — mỗi service 1 instance riêng
for DB in product order inventory payment notification; do
  helm install ${DB}-postgres bitnami/postgresql \
    --namespace robomart \
    --set auth.database=${DB}_db \
    --set auth.username=robomart \
    --set auth.password=<STRONG_PASSWORD> \
    --set primary.persistence.size=10Gi
done

# Redis
helm install redis bitnami/redis \
  --namespace robomart \
  --set auth.enabled=false \
  --set replica.replicaCount=1

# Kafka + Zookeeper
helm install kafka bitnami/kafka \
  --namespace robomart \
  --set replicaCount=1 \
  --set zookeeper.enabled=true

# Elasticsearch
helm install elasticsearch bitnami/elasticsearch \
  --namespace robomart \
  --set master.replicaCount=1 \
  --set data.replicaCount=1 \
  --set coordinating.replicaCount=0

# Keycloak
helm install keycloak bitnami/keycloak \
  --namespace robomart \
  --set auth.adminUser=admin \
  --set auth.adminPassword=<STRONG_PASSWORD>
```

### Option B: Môi trường ngoài cluster (đơn giản hơn cho bắt đầu)

Nếu có sẵn RDS, ElastiCache, MSK (AWS) hoặc tương đương — chỉ cần cập nhật `configmap.yml` và `secrets-template.yml` để trỏ ra ngoài. Phù hợp khi team đã có managed services.

---

## Bước 3 — Cập nhật image path trong deployment files

Hiện tại các deployment file đang dùng placeholder:

```yaml
# infra/k8s/services/product-service/deployment.yml
image: ghcr.io/robomart/product-service:latest
```

CD workflow push image lên `ghcr.io/{github_owner}/robomart/{service}`. Cần đổi cho đúng với GitHub username thật:

```bash
# Thay robomart bằng GitHub username thật (ví dụ: tuanpm2026)
find infra/k8s/services -name "deployment.yml" \
  -exec sed -i '' 's|ghcr.io/robomart/|ghcr.io/tuanpm2026/robomart/|g' {} \;
```

---

## Bước 4 — Tạo Secrets (KHÔNG commit file thật)

```bash
# Copy template ra, điền password thật vào, apply, rồi xóa file local
cp infra/k8s/base/secrets-template.yml /tmp/robomart-secrets.yml

# Mở /tmp/robomart-secrets.yml, thay tất cả REPLACE_ME bằng password thật
# Đặc biệt chú ý: notification-db-secret dùng user 'postgres', các service còn lại dùng 'robomart'

kubectl apply -f /tmp/robomart-secrets.yml -n robomart
rm /tmp/robomart-secrets.yml  # xóa ngay sau khi apply
```

Secrets được inject vào pod qua `secretRef` trong deployment:

```yaml
envFrom:
- configMapRef:
    name: robomart-config   # env vars không nhạy cảm
- secretRef:
    name: product-db-secret # SPRING_DATASOURCE_URL, USERNAME, PASSWORD
```

---

## Bước 5 — Cập nhật configmap.yml cho đúng địa chỉ

Nếu dùng Helm (bước 2A), Helm đặt tên service theo pattern `{release-name}.{namespace}.svc.cluster.local`. Cần cập nhật `infra/k8s/base/configmap.yml`:

```yaml
# Kafka (Helm release name: kafka)
SPRING_KAFKA_BOOTSTRAP_SERVERS: "kafka.robomart.svc.cluster.local:9092"

# Redis (Helm release name: redis)
SPRING_DATA_REDIS_HOST: "redis-master.robomart.svc.cluster.local"

# Elasticsearch (Helm release name: elasticsearch)
SPRING_ELASTICSEARCH_URIS: "http://elasticsearch.robomart.svc.cluster.local:9200"

# Keycloak (Helm release name: keycloak)
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI: "http://keycloak.robomart.svc.cluster.local/realms/robomart"

# PostgreSQL (mỗi service)
# Cập nhật trong secrets-template.yml tương ứng:
# product-postgres.robomart.svc.cluster.local:5432
# order-postgres.robomart.svc.cluster.local:5432
# ...
```

---

## Bước 6 — Apply base config và deploy

```bash
# Base
kubectl apply -f infra/k8s/base/namespace.yml
kubectl apply -f infra/k8s/base/configmap.yml

# Tất cả application services
kubectl apply -f infra/k8s/services/ --recursive -n robomart

# Kiểm tra
kubectl get pods -n robomart
kubectl get deployments -n robomart
kubectl get services -n robomart
```

---

## Bước 7 — Set secret KUBECONFIG cho GitHub Actions

CD workflow cần `KUBECONFIG` secret để `kubectl` kết nối được vào cluster:

```bash
# Lấy kubeconfig hiện tại, encode base64
cat ~/.kube/config | base64 | pbcopy   # macOS — copy vào clipboard

# Hoặc lưu ra file
cat ~/.kube/config | base64 > /tmp/kubeconfig-b64.txt
```

Vào **GitHub repo → Settings → Secrets and variables → Actions → New repository secret**:

- Name: `KUBECONFIG`
- Value: nội dung base64 ở trên

CD workflow dùng nó như sau (đã có sẵn trong `cd-deploy.yml`):

```yaml
- name: Configure K8s credentials
  run: |
    mkdir -p ~/.kube
    echo "${{ secrets.KUBECONFIG }}" | base64 -d > ~/.kube/config
```

> **Lưu ý bảo mật:** Kubeconfig có toàn quyền trên cluster. Nên tạo ServiceAccount riêng với quyền tối thiểu (chỉ `deployments` trong namespace `robomart`) thay vì dùng admin kubeconfig.

---

## Bước 8 — Cài Metrics Server (bắt buộc cho HPA)

HPA cần Metrics Server để đọc CPU utilization. Cluster managed (GKE, EKS, AKS) thường đã có sẵn. Nếu dùng minikube/kind:

```bash
# minikube
minikube addons enable metrics-server

# kind / bare cluster
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
```

Verify HPA hoạt động:

```bash
kubectl get hpa -n robomart
# NAME                REFERENCE                      TARGETS   MINPODS   MAXPODS   REPLICAS
# api-gateway         Deployment/api-gateway         15%/70%   2         5         2
# product-service     Deployment/product-service     22%/70%   2         5         2
```

---

## Tóm tắt checklist

```
□ 1. Cluster K8s đang chạy và kubectl trỏ đúng vào nó
□ 2. Infrastructure services deploy xong (PostgreSQL x5, Redis, Kafka, Elasticsearch, Keycloak)
□ 3. configmap.yml cập nhật địa chỉ đúng cho infra services
□ 4. Image path trong deployment files đúng với GitHub username
□ 5. Secrets apply xong vào namespace robomart (KHÔNG commit file thật)
□ 6. Secret KUBECONFIG set trong GitHub repo Settings
□ 7. Metrics Server cài xong (nếu cluster không có sẵn)
□ 8. Push code lên main → Backend CI pass → CD Deploy tự động chạy
```

---

## Verify luồng CD hoàn chỉnh

Sau khi setup xong, push bất kỳ thay đổi backend lên `main`:

```bash
# Xem CD workflow đang chạy
gh run list --workflow=cd-deploy.yml

# Theo dõi trực tiếp
gh run watch

# Sau khi CD xong, kiểm tra cluster
kubectl get pods -n robomart
kubectl rollout status deployment/api-gateway -n robomart
kubectl logs -f deployment/product-service -n robomart
```

Truy cập app qua external IP của api-gateway:

```bash
kubectl get service api-gateway -n robomart
# NAME          TYPE           CLUSTER-IP     EXTERNAL-IP      PORT(S)
# api-gateway   LoadBalancer   10.96.0.1      <EXTERNAL_IP>    8080:30080/TCP
```
