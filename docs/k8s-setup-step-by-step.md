# K8s Setup — Production (kubeadm)

Hướng dẫn này setup cluster K8s production-grade bằng kubeadm trên bare-metal hoặc cloud VMs.
Tất cả version được chọn là stable mới nhất tại thời điểm viết (2026-04).

---

## Phần 1 — Chuẩn bị máy chủ

### Yêu cầu phần cứng tối thiểu

| Node | Vai trò | CPU | RAM | Disk |
|---|---|---|---|---|
| `ctrl-01` | Control plane | 4 vCPU | 8 GB | 50 GB SSD |
| `worker-01` | Worker | 8 vCPU | 16 GB | 100 GB SSD |
| `worker-02` | Worker | 8 vCPU | 16 GB | 100 GB SSD |
| `worker-03` | Worker | 8 vCPU | 16 GB | 100 GB SSD |

> 3 worker nodes để HPA có chỗ scale, Elasticsearch và Kafka có chỗ phân tán.
> OS: **Ubuntu 24.04 LTS** trên tất cả nodes.

### Versions sử dụng

| Component | Version |
|---|---|
| Kubernetes | 1.33 |
| containerd | 2.1 |
| runc | 1.2 |
| CNI plugin (Cilium) | 1.17 |
| Helm | 3.17 |
| ArgoCD | 2.14 |
| PostgreSQL (Bitnami) | 17.x |
| Kafka (Bitnami) | 3.9 |
| Elasticsearch (Bitnami) | 8.x |
| Redis (Bitnami) | 7.x |
| Keycloak (Bitnami) | 26.x |

---

## Phần 2 — Setup tất cả nodes (chạy trên MỌI node)

SSH vào từng node và chạy toàn bộ block này.

### 2.1 — Tắt swap (K8s bắt buộc)

```bash
swapoff -a
sed -i '/swap/d' /etc/fstab
```

### 2.2 — Kernel modules và sysctl

```bash
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

### 2.3 — Cài containerd 2.1

```bash
# Cài dependencies
apt-get update && apt-get install -y apt-transport-https ca-certificates curl gpg

# Docker GPG key (containerd nằm trong Docker repo)
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

# Config containerd với SystemdCgroup (bắt buộc cho K8s)
mkdir -p /etc/containerd
containerd config default | tee /etc/containerd/config.toml
sed -i 's/SystemdCgroup = false/SystemdCgroup = true/' /etc/containerd/config.toml

systemctl restart containerd
systemctl enable containerd

# Verify
containerd --version
# containerd containerd.io 2.1.x
```

### 2.4 — Cài kubeadm, kubelet, kubectl 1.33

```bash
curl -fsSL https://pkgs.k8s.io/core:/stable:/v1.33/deb/Release.key \
  | gpg --dearmor -o /etc/apt/keyrings/kubernetes-apt-keyring.gpg

echo 'deb [signed-by=/etc/apt/keyrings/kubernetes-apt-keyring.gpg] \
  https://pkgs.k8s.io/core:/stable:/v1.33/deb/ /' \
  | tee /etc/apt/sources.list.d/kubernetes.list

apt-get update
apt-get install -y kubelet kubeadm kubectl
apt-mark hold kubelet kubeadm kubectl   # ngăn auto-upgrade

systemctl enable kubelet

# Verify
kubeadm version
kubectl version --client
```

---

## Phần 3 — Khởi tạo Control Plane (chỉ chạy trên ctrl-01)

```bash
# Pod CIDR dùng cho Cilium (không overlap với node/service network)
kubeadm init \
  --kubernetes-version=v1.33.0 \
  --pod-network-cidr=10.244.0.0/16 \
  --service-cidr=10.96.0.0/12 \
  --control-plane-endpoint=$(hostname -I | awk '{print $1}') \
  --upload-certs

# Lưu lại output — có join command cho worker nodes
```

Setup kubectl cho user hiện tại:
```bash
mkdir -p $HOME/.kube
cp -i /etc/kubernetes/admin.conf $HOME/.kube/config
chown $(id -u):$(id -g) $HOME/.kube/config
```

Verify control plane:
```bash
kubectl get nodes
# ctrl-01   NotReady   control-plane   ...
# NotReady là đúng — chưa có CNI
```

---

## Phần 4 — Cài Cilium CNI (trên ctrl-01)

Cilium thay thế Flannel/Calico — eBPF-based, hiệu năng cao hơn, có network policy và observability tốt hơn.

```bash
# Cài Helm trước
curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
helm version
# version.BuildInfo{Version:"v3.17.x"}

# Cài Cilium CLI
CILIUM_CLI_VERSION=$(curl -s https://raw.githubusercontent.com/cilium/cilium-cli/main/stable.txt)
curl -L --fail --remote-name-all \
  https://github.com/cilium/cilium-cli/releases/download/${CILIUM_CLI_VERSION}/cilium-linux-amd64.tar.gz
tar xzvf cilium-linux-amd64.tar.gz
mv cilium /usr/local/bin/

# Deploy Cilium 1.17
helm repo add cilium https://helm.cilium.io/
helm install cilium cilium/cilium \
  --version 1.17.0 \
  --namespace kube-system \
  --set kubeProxyReplacement=true \
  --set k8sServiceHost=$(hostname -I | awk '{print $1}') \
  --set k8sServicePort=6443 \
  --set hubble.relay.enabled=true \
  --set hubble.ui.enabled=true

# Chờ Cilium ready (~2 phút)
cilium status --wait

# Nodes phải chuyển sang Ready
kubectl get nodes
# ctrl-01   Ready   control-plane   ...
```

---

## Phần 5 — Join Worker Nodes (chạy trên worker-01, worker-02, worker-03)

Copy lệnh `kubeadm join` từ output của `kubeadm init` ở Phần 3, dạng:

```bash
kubeadm join <ctrl-01-ip>:6443 \
  --token <token> \
  --discovery-token-ca-cert-hash sha256:<hash>
```

Nếu token hết hạn (24h), tạo lại trên ctrl-01:
```bash
kubeadm token create --print-join-command
```

Verify trên ctrl-01:
```bash
kubectl get nodes -o wide
# NAME        STATUS   ROLES           AGE   VERSION
# ctrl-01     Ready    control-plane   10m   v1.33.0
# worker-01   Ready    <none>          2m    v1.33.0
# worker-02   Ready    <none>          2m    v1.33.0
# worker-03   Ready    <none>          2m    v1.33.0
```

---

## Phần 6 — Metrics Server (bắt buộc cho HPA)

```bash
helm repo add metrics-server https://kubernetes-sigs.github.io/metrics-server/
helm install metrics-server metrics-server/metrics-server \
  --namespace kube-system \
  --set args[0]="--kubelet-insecure-tls"  # cần nếu node dùng self-signed cert

# Verify
kubectl top nodes
kubectl top pods -A
```

---

## Phần 7 — Namespace & Helm repos

```bash
# Tạo namespace
kubectl apply -f infra/k8s/base/namespace.yml

# Thêm repos
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo update

# Verify
helm repo list
```

---

## Phần 8 — Infrastructure Services

Deploy theo đúng thứ tự. Chờ từng nhóm ready trước khi chuyển nhóm tiếp theo.

### 8.1 — PostgreSQL 17 (5 instances)

```bash
# product_db — user: robomart
helm install product-postgres bitnami/postgresql \
  --namespace robomart \
  --version 16.x \
  --set image.tag=17 \
  --set auth.database=product_db \
  --set auth.username=robomart \
  --set auth.password=<STRONG_PASSWORD> \
  --set primary.persistence.size=20Gi \
  --set primary.persistence.storageClass=standard \
  --set primary.resources.requests.memory=256Mi \
  --set primary.resources.requests.cpu=250m \
  --set primary.resources.limits.memory=512Mi \
  --set primary.resources.limits.cpu=500m

# order_db — user: robomart
helm install order-postgres bitnami/postgresql \
  --namespace robomart \
  --version 16.x \
  --set image.tag=17 \
  --set auth.database=order_db \
  --set auth.username=robomart \
  --set auth.password=<STRONG_PASSWORD> \
  --set primary.persistence.size=20Gi \
  --set primary.resources.requests.memory=256Mi \
  --set primary.resources.requests.cpu=250m \
  --set primary.resources.limits.memory=512Mi \
  --set primary.resources.limits.cpu=500m

# inventory_db — user: robomart
helm install inventory-postgres bitnami/postgresql \
  --namespace robomart \
  --version 16.x \
  --set image.tag=17 \
  --set auth.database=inventory_db \
  --set auth.username=robomart \
  --set auth.password=<STRONG_PASSWORD> \
  --set primary.persistence.size=20Gi \
  --set primary.resources.requests.memory=256Mi \
  --set primary.resources.requests.cpu=250m \
  --set primary.resources.limits.memory=512Mi \
  --set primary.resources.limits.cpu=500m

# payment_db — user: robomart
helm install payment-postgres bitnami/postgresql \
  --namespace robomart \
  --version 16.x \
  --set image.tag=17 \
  --set auth.database=payment_db \
  --set auth.username=robomart \
  --set auth.password=<STRONG_PASSWORD> \
  --set primary.persistence.size=20Gi \
  --set primary.resources.requests.memory=256Mi \
  --set primary.resources.requests.cpu=250m \
  --set primary.resources.limits.memory=512Mi \
  --set primary.resources.limits.cpu=500m

# notification_db — user: postgres (khác các service còn lại!)
helm install notification-postgres bitnami/postgresql \
  --namespace robomart \
  --version 16.x \
  --set image.tag=17 \
  --set auth.database=notification_db \
  --set auth.username=postgres \
  --set auth.password=<STRONG_PASSWORD> \
  --set primary.persistence.size=10Gi \
  --set primary.resources.requests.memory=256Mi \
  --set primary.resources.requests.cpu=250m \
  --set primary.resources.limits.memory=512Mi \
  --set primary.resources.limits.cpu=500m
```

Chờ tất cả PostgreSQL ready:
```bash
kubectl wait pod \
  -l app.kubernetes.io/name=postgresql \
  --for=condition=Ready \
  --timeout=300s \
  -n robomart
```

### 8.2 — Redis 7

```bash
helm install redis bitnami/redis \
  --namespace robomart \
  --set image.tag=7.4 \
  --set auth.enabled=false \
  --set replica.replicaCount=1 \
  --set master.persistence.size=8Gi \
  --set master.resources.requests.memory=256Mi \
  --set master.resources.requests.cpu=250m \
  --set master.resources.limits.memory=512Mi \
  --set master.resources.limits.cpu=500m
```

### 8.3 — Kafka 3.9 (KRaft — không cần Zookeeper)

```bash
helm install kafka bitnami/kafka \
  --namespace robomart \
  --set image.tag=3.9 \
  --set kraft.enabled=true \
  --set replicaCount=3 \
  --set controller.replicaCount=3 \
  --set listeners.client.protocol=PLAINTEXT \
  --set listeners.interbroker.protocol=PLAINTEXT \
  --set persistence.size=20Gi \
  --set resources.requests.memory=1Gi \
  --set resources.requests.cpu=500m \
  --set resources.limits.memory=2Gi \
  --set resources.limits.cpu=1000m
```

### 8.4 — Schema Registry

```bash
# Lấy tên service Kafka thật
kubectl get svc -n robomart | grep kafka

helm install schema-registry bitnami/schema-registry \
  --namespace robomart \
  --set kafka.enabled=false \
  --set externalKafka.brokers=kafka.robomart.svc.cluster.local:9092 \
  --set resources.requests.memory=256Mi \
  --set resources.requests.cpu=250m \
  --set resources.limits.memory=512Mi \
  --set resources.limits.cpu=500m
```

### 8.5 — Elasticsearch 8

```bash
helm install elasticsearch bitnami/elasticsearch \
  --namespace robomart \
  --set image.tag=8.17.0 \
  --set master.replicaCount=1 \
  --set data.replicaCount=2 \
  --set coordinating.replicaCount=1 \
  --set security.enabled=false \
  --set master.persistence.size=10Gi \
  --set data.persistence.size=30Gi \
  --set data.resources.requests.memory=1Gi \
  --set data.resources.requests.cpu=500m \
  --set data.resources.limits.memory=2Gi \
  --set data.resources.limits.cpu=1000m
```

### 8.6 — Keycloak 26

```bash
# DB riêng cho Keycloak
helm install keycloak-db bitnami/postgresql \
  --namespace robomart \
  --set image.tag=17 \
  --set auth.database=keycloak \
  --set auth.username=keycloak \
  --set auth.password=<STRONG_PASSWORD> \
  --set primary.persistence.size=10Gi

# Chờ DB ready
kubectl wait pod -l app.kubernetes.io/instance=keycloak-db \
  --for=condition=Ready --timeout=120s -n robomart

# Keycloak 26.x
helm install keycloak bitnami/keycloak \
  --namespace robomart \
  --set image.tag=26.1.4 \
  --set auth.adminUser=admin \
  --set auth.adminPassword=<STRONG_PASSWORD> \
  --set postgresql.enabled=false \
  --set externalDatabase.host=keycloak-db-postgresql.robomart.svc.cluster.local \
  --set externalDatabase.database=keycloak \
  --set externalDatabase.user=keycloak \
  --set externalDatabase.password=<STRONG_PASSWORD> \
  --set replicaCount=2 \
  --set resources.requests.memory=512Mi \
  --set resources.requests.cpu=500m \
  --set resources.limits.memory=1Gi \
  --set resources.limits.cpu=1000m
```

Import realm robomart vào Keycloak:
```bash
# Port-forward tạm để import
kubectl port-forward svc/keycloak -n robomart 8180:80 &

# Dùng Keycloak Admin CLI
curl -s http://localhost:8180/realms/master/protocol/openid-connect/token \
  -d "client_id=admin-cli&grant_type=password&username=admin&password=<STRONG_PASSWORD>" \
  | jq -r .access_token > /tmp/kc-token.txt

curl -X POST http://localhost:8180/admin/realms \
  -H "Authorization: Bearer $(cat /tmp/kc-token.txt)" \
  -H "Content-Type: application/json" \
  -d @infra/docker/keycloak/robomart-realm.json

rm /tmp/kc-token.txt
kill %1   # dừng port-forward
```

Verify toàn bộ infra:
```bash
kubectl get pods -n robomart
# Tất cả STATUS = Running
```

---

## Phần 9 — ConfigMap và Secrets

### 9.1 — Xác nhận tên service thật

```bash
kubectl get svc -n robomart
```

Output mẫu:
```
NAME                              TYPE        CLUSTER-IP    PORT(S)
elasticsearch                     ClusterIP   10.96.x.x     9200/TCP
kafka                             ClusterIP   10.96.x.x     9092/TCP
keycloak                          ClusterIP   10.96.x.x     80/TCP
keycloak-db-postgresql            ClusterIP   10.96.x.x     5432/TCP
notification-postgres-postgresql  ClusterIP   10.96.x.x     5432/TCP
order-postgres-postgresql         ClusterIP   10.96.x.x     5432/TCP
...
redis-master                      ClusterIP   10.96.x.x     6379/TCP
schema-registry                   ClusterIP   10.96.x.x     8081/TCP
```

### 9.2 — Cập nhật configmap.yml

Sửa [infra/k8s/base/configmap.yml](../infra/k8s/base/configmap.yml) với địa chỉ thật từ bước trên:

```yaml
data:
  SPRING_KAFKA_BOOTSTRAP_SERVERS: "kafka.robomart.svc.cluster.local:9092"
  KAFKA_BOOTSTRAP_SERVERS:        "kafka.robomart.svc.cluster.local:9092"

  SPRING_KAFKA_PROPERTIES_SCHEMA_REGISTRY_URL: "http://schema-registry.robomart.svc.cluster.local:8081"
  SCHEMA_REGISTRY_URL:                         "http://schema-registry.robomart.svc.cluster.local:8081"

  SPRING_DATA_REDIS_HOST: "redis-master.robomart.svc.cluster.local"
  SPRING_DATA_REDIS_PORT: "6379"

  SPRING_ELASTICSEARCH_URIS: "http://elasticsearch.robomart.svc.cluster.local:9200"

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

Mở file, sửa từng giá trị:

| Field | Giá trị |
|---|---|
| `SPRING_DATASOURCE_URL` (product) | `jdbc:postgresql://product-postgres-postgresql.robomart.svc.cluster.local:5432/product_db` |
| `SPRING_DATASOURCE_URL` (order) | `jdbc:postgresql://order-postgres-postgresql.robomart.svc.cluster.local:5432/order_db` |
| `SPRING_DATASOURCE_URL` (inventory) | `jdbc:postgresql://inventory-postgres-postgresql.robomart.svc.cluster.local:5432/inventory_db` |
| `SPRING_DATASOURCE_URL` (payment) | `jdbc:postgresql://payment-postgres-postgresql.robomart.svc.cluster.local:5432/payment_db` |
| `SPRING_DATASOURCE_URL` (notification) | `jdbc:postgresql://notification-postgres-postgresql.robomart.svc.cluster.local:5432/notification_db` |
| Tất cả `REPLACE_ME` | Password đã đặt ở Phần 8 |

```bash
kubectl apply -f /tmp/robomart-secrets.yml -n robomart
rm /tmp/robomart-secrets.yml   # xóa ngay, không để lại

kubectl get secrets -n robomart
```

---

## Phần 10 — ArgoCD 2.14

### 10.1 — Cài ArgoCD

```bash
kubectl create namespace argocd

kubectl apply -n argocd \
  -f https://raw.githubusercontent.com/argoproj/argo-cd/v2.14.0/manifests/install.yaml

kubectl wait --for=condition=available deployment/argocd-server \
  -n argocd --timeout=300s
```

### 10.2 — Cài ArgoCD CLI

```bash
curl -sSL -o /usr/local/bin/argocd \
  https://github.com/argoproj/argo-cd/releases/download/v2.14.0/argocd-linux-amd64
chmod +x /usr/local/bin/argocd

argocd version --client
```

### 10.3 — Login và đổi password

```bash
ARGOCD_PASS=$(kubectl get secret argocd-initial-admin-secret \
  -n argocd -o jsonpath="{.data.password}" | base64 -d)

kubectl port-forward svc/argocd-server -n argocd 8090:443 &

argocd login localhost:8090 \
  --username admin \
  --password "$ARGOCD_PASS" \
  --insecure

# Đổi ngay
argocd account update-password \
  --current-password "$ARGOCD_PASS" \
  --new-password "<NEW_STRONG_PASSWORD>"

kill %1
```

### 10.4 — Kết nối GitHub repo

```bash
argocd repo add https://github.com/tuanpm2026/robo-mart \
  --username tuanpm2026 \
  --password <GITHUB_PAT>
# GitHub PAT cần scope: repo (read)
```

### 10.5 — Deploy Application

```bash
kubectl apply -f infra/k8s/argocd/robomart-app.yaml

# Xem trạng thái
argocd app get robomart-production

# Force sync lần đầu không cần chờ poll 3 phút
argocd app sync robomart-production
```

---

## Phần 11 — Verify toàn bộ stack

```bash
# Tất cả pods Running
kubectl get pods -n robomart

# Deployments đủ replicas
kubectl get deployments -n robomart

# HPA đang đọc metrics
kubectl get hpa -n robomart
# NAME                TARGETS    MINPODS  MAXPODS  REPLICAS
# api-gateway         15%/70%    2        5        2
# product-service     22%/70%    2        5        2
# ...

# ArgoCD status
argocd app list
# NAME                  CLUSTER    NAMESPACE  STATUS  HEALTH
# robomart-production   in-cluster robomart   Synced  Healthy

# External IP api-gateway
kubectl get svc api-gateway -n robomart
GATEWAY_IP=$(kubectl get svc api-gateway -n robomart \
  -o jsonpath='{.status.loadBalancer.ingress[0].ip}')

curl http://${GATEWAY_IP}:8080/actuator/health
# {"status":"UP","groups":["liveness","readiness"]}
```

---

## Phần 12 — Trigger CD lần đầu

```bash
git commit --allow-empty -m "chore: trigger initial CD deploy"
git push
```

Theo dõi:
```bash
# GitHub Actions: build + push images + commit kustomization.yaml
gh run watch

# ArgoCD tự phát hiện commit mới (3 phút) hoặc force:
argocd app sync robomart-production --force

# Xem rolling update
kubectl rollout status deployment/api-gateway -n robomart
kubectl rollout status deployment/product-service -n robomart
```

---

## Phần 13 — Thứ tự dependency

```
[ctrl-01] Control plane + Cilium CNI
      │
      └── [worker-01/02/03] join cluster
                │
                ├── Metrics Server
                ├── Namespace: robomart
                │
                ├── Phase A — Storage (không phụ thuộc nhau, deploy song song)
                │   ├── product-postgres
                │   ├── order-postgres
                │   ├── inventory-postgres
                │   ├── payment-postgres
                │   ├── notification-postgres
                │   ├── keycloak-db
                │   ├── redis
                │   └── elasticsearch
                │
                ├── Phase B — Messaging (cần Phase A xong trước)
                │   ├── kafka
                │   └── schema-registry (cần kafka)
                │
                ├── Phase C — Auth (cần keycloak-db xong)
                │   └── keycloak → import robomart-realm.json
                │
                ├── Phase D — Config (cần biết địa chỉ service thật)
                │   ├── kubectl apply configmap.yml
                │   └── kubectl apply secrets (từ template)
                │
                ├── Phase E — ArgoCD
                │   └── kubectl apply robomart-app.yaml
                │         └── ArgoCD sync → deploy 7 app services
                │               (cart, product, order, inventory,
                │                payment, notification, api-gateway)
                │
                └── Phase F — Verify + trigger CD lần đầu
```

---

## Troubleshooting

```bash
# Pod không start
kubectl describe pod <name> -n robomart
kubectl logs <name> -n robomart --previous

# Test DNS nội bộ cluster
kubectl run -it --rm debug --image=busybox:1.36 \
  --restart=Never -n robomart -- \
  nslookup kafka.robomart.svc.cluster.local

# Kiểm tra service endpoint thật
kubectl get endpoints -n robomart

# ArgoCD diff trước khi sync
argocd app diff robomart-production

# HPA không đọc được metrics
kubectl describe hpa api-gateway -n robomart
kubectl top nodes

# Xem log ArgoCD application controller
kubectl logs -n argocd \
  deployment/argocd-application-controller --tail=50

# Reset về trạng thái git nếu ai đó sửa tay cluster
argocd app sync robomart-production --force
```
