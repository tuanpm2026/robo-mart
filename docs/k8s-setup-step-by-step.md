# K8s Setup — Single Node Production (kubeadm)

1 máy chủ duy nhất đóng vai trò vừa control plane vừa worker.
Phù hợp cho staging / demo / small production.

---

## Yêu cầu phần cứng tối thiểu

| | Tối thiểu | Khuyến nghị |
|---|---|---|
| CPU | 8 vCPU | 16 vCPU |
| RAM | 16 GB | 32 GB |
| Disk | 100 GB SSD | 200 GB SSD |
| OS | Ubuntu 24.04 LTS | Ubuntu 24.04 LTS |

> Stack này chạy: 7 app services + PostgreSQL×5 + Kafka×1 + Redis + Elasticsearch + Keycloak + ArgoCD.
> Ước tính RAM cần: ~14 GB khi full load. 16 GB là minimum, 32 GB thoải mái hơn.

---

## Versions

| Component | Version |
|---|---|
| Kubernetes | 1.33 |
| containerd | 2.1 |
| Cilium CNI | 1.17 |
| Helm | 3.17 |
| ArgoCD | 2.14 |
| PostgreSQL | 17 |
| Kafka | 3.9 (KRaft) |
| Elasticsearch | 8.17 |
| Redis | 7.4 |
| Keycloak | 26.1.4 |

---

## Phần 1 — Chuẩn bị hệ thống

```bash
# Tắt swap — K8s bắt buộc
swapoff -a
sed -i '/swap/d' /etc/fstab

# Kernel modules
cat <<EOF | tee /etc/modules-load.d/k8s.conf
overlay
br_netfilter
EOF

modprobe overlay
modprobe br_netfilter

cat <<EOF | tee /etc/sysctl.d/k8s.conf
net.bridge.bridge-nf-call-iptables  = 1
net.bridge.bridge-nf-call-ip6tables = 1
net.ipv4.ip_forward                 = 1
EOF

sysctl --system
```

---

## Phần 2 — Cài containerd

```bash
apt-get update && apt-get install -y apt-transport-https ca-certificates curl gpg

install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc

echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
  https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  | tee /etc/apt/sources.list.d/docker.list

apt-get update
apt-get install -y containerd.io

# SystemdCgroup = true — bắt buộc cho K8s
mkdir -p /etc/containerd
containerd config default | tee /etc/containerd/config.toml
sed -i 's/SystemdCgroup = false/SystemdCgroup = true/' /etc/containerd/config.toml

systemctl restart containerd
systemctl enable containerd
containerd --version
```

---

## Phần 3 — Cài kubeadm, kubelet, kubectl

```bash
curl -fsSL https://pkgs.k8s.io/core:/stable:/v1.33/deb/Release.key \
  | gpg --dearmor -o /etc/apt/keyrings/kubernetes-apt-keyring.gpg

echo 'deb [signed-by=/etc/apt/keyrings/kubernetes-apt-keyring.gpg] \
  https://pkgs.k8s.io/core:/stable:/v1.33/deb/ /' \
  | tee /etc/apt/sources.list.d/kubernetes.list

apt-get update
apt-get install -y kubelet kubeadm kubectl
apt-mark hold kubelet kubeadm kubectl

systemctl enable kubelet
kubeadm version
```

---

## Phần 4 — Khởi tạo cluster

```bash
# Lấy IP của máy
SERVER_IP=$(hostname -I | awk '{print $1}')
echo "Server IP: $SERVER_IP"

kubeadm init \
  --kubernetes-version=v1.33.0 \
  --pod-network-cidr=10.244.0.0/16 \
  --service-cidr=10.96.0.0/12 \
  --control-plane-endpoint=$SERVER_IP

# Setup kubectl
mkdir -p $HOME/.kube
cp -i /etc/kubernetes/admin.conf $HOME/.kube/config
chown $(id -u):$(id -g) $HOME/.kube/config
```

### Untaint control plane — cho phép pod chạy trên node này

Mặc định K8s không schedule workload lên control plane node.
Single-node cần bỏ taint này:

```bash
kubectl taint nodes --all node-role.kubernetes.io/control-plane-
# node/<hostname> untainted

# Verify node sẵn sàng nhận workload
kubectl describe node $(hostname) | grep Taint
# Taints: <none>
```

---

## Phần 5 — Cài Cilium CNI

```bash
# Helm
curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash

# Cilium CLI
CILIUM_CLI_VERSION=$(curl -s https://raw.githubusercontent.com/cilium/cilium-cli/main/stable.txt)
curl -L --remote-name \
  https://github.com/cilium/cilium-cli/releases/download/${CILIUM_CLI_VERSION}/cilium-linux-amd64.tar.gz
tar xzvf cilium-linux-amd64.tar.gz && mv cilium /usr/local/bin/

# Deploy Cilium
helm repo add cilium https://helm.cilium.io/
helm install cilium cilium/cilium \
  --version 1.17.0 \
  --namespace kube-system \
  --set kubeProxyReplacement=true \
  --set k8sServiceHost=$SERVER_IP \
  --set k8sServicePort=6443

# Chờ ready (~2 phút)
cilium status --wait

kubectl get nodes
# NAME       STATUS   ROLES           VERSION
# <host>     Ready    control-plane   v1.33.0
```

---

## Phần 6 — Metrics Server

```bash
helm repo add metrics-server https://kubernetes-sigs.github.io/metrics-server/
helm install metrics-server metrics-server/metrics-server \
  --namespace kube-system \
  --set args[0]="--kubelet-insecure-tls"

# Verify (chờ ~30s)
kubectl top nodes
```

---

## Phần 7 — Namespace & Helm repos

```bash
kubectl apply -f infra/k8s/base/namespace.yml

helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo update
```

---

## Phần 8 — Infrastructure Services

Single node nên giảm replicas xuống 1, resource requests thấp hơn để tiết kiệm RAM.

### 8.1 — PostgreSQL 17 (5 instances)

```bash
for DB_CONFIG in \
  "product-postgres:product_db:robomart" \
  "order-postgres:order_db:robomart" \
  "inventory-postgres:inventory_db:robomart" \
  "payment-postgres:payment_db:robomart"; do

  RELEASE=$(echo $DB_CONFIG | cut -d: -f1)
  DATABASE=$(echo $DB_CONFIG | cut -d: -f2)
  USERNAME=$(echo $DB_CONFIG | cut -d: -f3)

  helm install $RELEASE bitnami/postgresql \
    --namespace robomart \
    --set image.tag=17 \
    --set auth.database=$DATABASE \
    --set auth.username=$USERNAME \
    --set auth.password=<STRONG_PASSWORD> \
    --set primary.persistence.size=10Gi \
    --set primary.resources.requests.memory=128Mi \
    --set primary.resources.requests.cpu=100m \
    --set primary.resources.limits.memory=256Mi \
    --set primary.resources.limits.cpu=300m
done

# notification_db — username khác: postgres
helm install notification-postgres bitnami/postgresql \
  --namespace robomart \
  --set image.tag=17 \
  --set auth.database=notification_db \
  --set auth.username=postgres \
  --set auth.password=<STRONG_PASSWORD> \
  --set primary.persistence.size=5Gi \
  --set primary.resources.requests.memory=128Mi \
  --set primary.resources.requests.cpu=100m \
  --set primary.resources.limits.memory=256Mi \
  --set primary.resources.limits.cpu=300m

# Chờ tất cả PostgreSQL ready
kubectl wait pod \
  -l app.kubernetes.io/name=postgresql \
  --for=condition=Ready --timeout=300s -n robomart
```

### 8.2 — Redis 7

```bash
helm install redis bitnami/redis \
  --namespace robomart \
  --set image.tag=7.4 \
  --set auth.enabled=false \
  --set replica.replicaCount=0 \
  --set master.persistence.size=5Gi \
  --set master.resources.requests.memory=128Mi \
  --set master.resources.requests.cpu=100m \
  --set master.resources.limits.memory=256Mi \
  --set master.resources.limits.cpu=300m
```

> `replica.replicaCount=0` — single node không cần replica Redis.

### 8.3 — Kafka 3.9 (KRaft, single broker)

```bash
helm install kafka bitnami/kafka \
  --namespace robomart \
  --set image.tag=3.9 \
  --set kraft.enabled=true \
  --set replicaCount=1 \
  --set controller.replicaCount=1 \
  --set listeners.client.protocol=PLAINTEXT \
  --set listeners.interbroker.protocol=PLAINTEXT \
  --set persistence.size=10Gi \
  --set resources.requests.memory=512Mi \
  --set resources.requests.cpu=250m \
  --set resources.limits.memory=1Gi \
  --set resources.limits.cpu=500m
```

### 8.4 — Schema Registry

```bash
helm install schema-registry bitnami/schema-registry \
  --namespace robomart \
  --set kafka.enabled=false \
  --set externalKafka.brokers=kafka.robomart.svc.cluster.local:9092 \
  --set resources.requests.memory=128Mi \
  --set resources.requests.cpu=100m \
  --set resources.limits.memory=256Mi \
  --set resources.limits.cpu=300m
```

### 8.5 — Elasticsearch 8

```bash
helm install elasticsearch bitnami/elasticsearch \
  --namespace robomart \
  --set image.tag=8.17.0 \
  --set master.replicaCount=1 \
  --set data.replicaCount=1 \
  --set coordinating.replicaCount=0 \
  --set ingest.enabled=false \
  --set security.enabled=false \
  --set master.persistence.size=10Gi \
  --set data.persistence.size=20Gi \
  --set data.resources.requests.memory=1Gi \
  --set data.resources.requests.cpu=500m \
  --set data.resources.limits.memory=2Gi \
  --set data.resources.limits.cpu=1000m \
  --set master.resources.requests.memory=512Mi \
  --set master.resources.requests.cpu=250m \
  --set master.resources.limits.memory=1Gi \
  --set master.resources.limits.cpu=500m
```

### 8.6 — Keycloak 26

```bash
# Keycloak DB
helm install keycloak-db bitnami/postgresql \
  --namespace robomart \
  --set image.tag=17 \
  --set auth.database=keycloak \
  --set auth.username=keycloak \
  --set auth.password=<STRONG_PASSWORD> \
  --set primary.persistence.size=5Gi \
  --set primary.resources.requests.memory=128Mi \
  --set primary.resources.requests.cpu=100m \
  --set primary.resources.limits.memory=256Mi \
  --set primary.resources.limits.cpu=300m

kubectl wait pod -l app.kubernetes.io/instance=keycloak-db \
  --for=condition=Ready --timeout=120s -n robomart

# Keycloak — single replica
helm install keycloak bitnami/keycloak \
  --namespace robomart \
  --set image.tag=26.1.4 \
  --set auth.adminUser=admin \
  --set auth.adminPassword=<STRONG_PASSWORD> \
  --set replicaCount=1 \
  --set postgresql.enabled=false \
  --set externalDatabase.host=keycloak-db-postgresql.robomart.svc.cluster.local \
  --set externalDatabase.database=keycloak \
  --set externalDatabase.user=keycloak \
  --set externalDatabase.password=<STRONG_PASSWORD> \
  --set resources.requests.memory=512Mi \
  --set resources.requests.cpu=250m \
  --set resources.limits.memory=1Gi \
  --set resources.limits.cpu=500m
```

Import realm:
```bash
kubectl port-forward svc/keycloak -n robomart 8180:80 &

# Lấy token
TOKEN=$(curl -s http://localhost:8180/realms/master/protocol/openid-connect/token \
  -d "client_id=admin-cli&grant_type=password&username=admin&password=<STRONG_PASSWORD>" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

# Import realm
curl -X POST http://localhost:8180/admin/realms \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d @infra/docker/keycloak/robomart-realm.json

echo "Realm imported"
kill %1
```

Verify toàn bộ infra:
```bash
kubectl get pods -n robomart
# Tất cả Running — mất khoảng 5-10 phút từ đầu đến đây
```

---

## Phần 9 — ConfigMap và Secrets

### 9.1 — Xác nhận tên service thật

```bash
kubectl get svc -n robomart
```

### 9.2 — Cập nhật configmap.yml

Sửa [infra/k8s/base/configmap.yml](../infra/k8s/base/configmap.yml):

```yaml
data:
  SPRING_KAFKA_BOOTSTRAP_SERVERS:              "kafka.robomart.svc.cluster.local:9092"
  KAFKA_BOOTSTRAP_SERVERS:                     "kafka.robomart.svc.cluster.local:9092"
  SPRING_KAFKA_PROPERTIES_SCHEMA_REGISTRY_URL: "http://schema-registry.robomart.svc.cluster.local:8081"
  SCHEMA_REGISTRY_URL:                         "http://schema-registry.robomart.svc.cluster.local:8081"
  SPRING_DATA_REDIS_HOST:                      "redis-master.robomart.svc.cluster.local"
  SPRING_DATA_REDIS_PORT:                      "6379"
  SPRING_ELASTICSEARCH_URIS:                   "http://elasticsearch.robomart.svc.cluster.local:9200"
  SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI: "http://keycloak.robomart.svc.cluster.local/realms/robomart"
  KEYCLOAK_JWK_SET_URI: "http://keycloak.robomart.svc.cluster.local/realms/robomart/protocol/openid-connect/certs"
```

```bash
kubectl apply -f infra/k8s/base/configmap.yml
```

### 9.3 — Apply Secrets

```bash
cp infra/k8s/base/secrets-template.yml /tmp/robomart-secrets.yml
```

Sửa URLs và passwords trong file:

| Secret | `SPRING_DATASOURCE_URL` |
|---|---|
| product-db-secret | `jdbc:postgresql://product-postgres-postgresql.robomart.svc.cluster.local:5432/product_db` |
| order-db-secret | `jdbc:postgresql://order-postgres-postgresql.robomart.svc.cluster.local:5432/order_db` |
| inventory-db-secret | `jdbc:postgresql://inventory-postgres-postgresql.robomart.svc.cluster.local:5432/inventory_db` |
| payment-db-secret | `jdbc:postgresql://payment-postgres-postgresql.robomart.svc.cluster.local:5432/payment_db` |
| notification-db-secret | `jdbc:postgresql://notification-postgres-postgresql.robomart.svc.cluster.local:5432/notification_db` |

```bash
kubectl apply -f /tmp/robomart-secrets.yml -n robomart
rm /tmp/robomart-secrets.yml
```

---

## Phần 10 — Giảm replicas app services xuống 1

Deployment files mặc định `replicas: 2`. Single node với RAM hạn chế nên chạy 1 replica mỗi service.
Thêm patch vào Kustomize overlay:

```bash
cat <<EOF > infra/k8s/overlays/production/patches/replicas.yaml
- op: replace
  path: /spec/replicas
  value: 1
EOF
```

Tạo file patch dạng strategic merge thay thế, đơn giản hơn:

```bash
mkdir -p infra/k8s/overlays/production/patches

cat <<'EOF' > infra/k8s/overlays/production/patches/single-node-replicas.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: api-gateway
  namespace: robomart
spec:
  replicas: 1
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: product-service
  namespace: robomart
spec:
  replicas: 1
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: cart-service
  namespace: robomart
spec:
  replicas: 1
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: order-service
  namespace: robomart
spec:
  replicas: 1
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: inventory-service
  namespace: robomart
spec:
  replicas: 1
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: payment-service
  namespace: robomart
spec:
  replicas: 1
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: notification-service
  namespace: robomart
spec:
  replicas: 1
EOF
```

Thêm patch vào `overlays/production/kustomization.yaml`:

```bash
cat >> infra/k8s/overlays/production/kustomization.yaml <<'EOF'

patches:
  - path: patches/single-node-replicas.yaml
EOF
```

Commit và push để ArgoCD nhận:
```bash
git add infra/k8s/overlays/production/patches/single-node-replicas.yaml
git add infra/k8s/overlays/production/kustomization.yaml
git commit -m "chore: set replicas=1 for single-node cluster"
git push
```

---

## Phần 11 — ArgoCD 2.14

```bash
kubectl create namespace argocd
kubectl apply -n argocd \
  -f https://raw.githubusercontent.com/argoproj/argo-cd/v2.14.0/manifests/install.yaml

kubectl wait --for=condition=available deployment/argocd-server \
  -n argocd --timeout=300s

# ArgoCD CLI
curl -sSL -o /usr/local/bin/argocd \
  https://github.com/argoproj/argo-cd/releases/download/v2.14.0/argocd-linux-amd64
chmod +x /usr/local/bin/argocd

# Login
ARGOCD_PASS=$(kubectl get secret argocd-initial-admin-secret \
  -n argocd -o jsonpath="{.data.password}" | base64 -d)

kubectl port-forward svc/argocd-server -n argocd 8090:443 &

argocd login localhost:8090 \
  --username admin --password "$ARGOCD_PASS" --insecure

argocd account update-password \
  --current-password "$ARGOCD_PASS" \
  --new-password "<NEW_STRONG_PASSWORD>"

kill %1

# Kết nối repo
argocd login localhost:8090 --username admin \
  --password "<NEW_STRONG_PASSWORD>" --insecure &
argocd repo add https://github.com/tuanpm2026/robo-mart \
  --username tuanpm2026 \
  --password <GITHUB_PAT>

# Deploy
kubectl apply -f infra/k8s/argocd/robomart-app.yaml
argocd app sync robomart-production
```

---

## Phần 12 — Truy cập từ bên ngoài

`api-gateway` service type `LoadBalancer` sẽ **pending** trên bare-metal vì không có cloud load balancer.
Dùng **NodePort** hoặc **MetalLB** thay thế:

### Option A — NodePort (đơn giản nhất)

```bash
kubectl patch svc api-gateway -n robomart \
  -p '{"spec": {"type": "NodePort", "ports": [{"port": 8080, "nodePort": 30080}]}}'

# Truy cập qua IP máy chủ
curl http://<SERVER_IP>:30080/actuator/health
```

### Option B — MetalLB (recommended, giữ được type LoadBalancer)

```bash
helm repo add metallb https://metallb.github.io/metallb
helm install metallb metallb/metallb --namespace metallb-system --create-namespace

# Cấp 1 IP từ dải IP của server cho LoadBalancer
cat <<EOF | kubectl apply -f -
apiVersion: metallb.io/v1beta1
kind: IPAddressPool
metadata:
  name: robomart-pool
  namespace: metallb-system
spec:
  addresses:
  - <SERVER_IP>/32          # IP của máy chủ
---
apiVersion: metallb.io/v1beta1
kind: L2Advertisement
metadata:
  name: robomart-l2
  namespace: metallb-system
spec:
  ipAddressPools:
  - robomart-pool
EOF

# api-gateway sẽ nhận EXTERNAL-IP = SERVER_IP
kubectl get svc api-gateway -n robomart
curl http://<SERVER_IP>:8080/actuator/health
```

---

## Phần 13 — Verify và trigger CD lần đầu

```bash
# Tất cả pods Running
kubectl get pods -n robomart

# ArgoCD sync thành công
argocd app list

# HPA
kubectl get hpa -n robomart

# Trigger CD
git commit --allow-empty -m "chore: trigger initial CD deploy"
git push

gh run watch
argocd app sync robomart-production --force
```

---

## Ước tính tài nguyên sử dụng (single node, replicas=1)

| Component | RAM |
|---|---|
| 7 app services × 256Mi | ~1.8 GB |
| PostgreSQL × 6 × 256Mi | ~1.5 GB |
| Kafka | ~1 GB |
| Elasticsearch | ~3 GB |
| Redis | ~256 MB |
| Keycloak | ~1 GB |
| ArgoCD + system pods | ~1 GB |
| **Tổng** | **~10-11 GB** |

→ **16 GB RAM là đủ**, 32 GB thoải mái có buffer để scale.

---

## Troubleshooting

```bash
# Xem resource usage thực tế
kubectl top pods -n robomart --sort-by=memory

# Pod bị OOMKilled (hết RAM)
kubectl describe pod <name> -n robomart | grep -A5 "OOM\|Limits\|Requests"
# Tăng limits trong deployment hoặc giảm service khác

# Pod Pending — không đủ CPU/RAM trên node
kubectl describe pod <name> -n robomart | grep -A10 "Events:"

# Test DNS nội bộ
kubectl run -it --rm debug --image=busybox:1.36 \
  --restart=Never -n robomart -- \
  nslookup kafka.robomart.svc.cluster.local

# ArgoCD out of sync
argocd app diff robomart-production
argocd app sync robomart-production --force

# Xem log app service
kubectl logs -f deployment/product-service -n robomart
```
