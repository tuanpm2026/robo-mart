# ArgoCD & GitOps — Robo-Mart

## Vấn đề với CD hiện tại

CD workflow hiện tại hoạt động theo kiểu **push-based**:

```
GitHub Actions → kubectl set image → cluster
```

Cụ thể trong `cd-deploy.yml`:
```bash
kubectl set image deployment/${SERVICE} ${SERVICE}=${REGISTRY}/${SERVICE}:${IMAGE_TAG} -n robomart
```

Đây là anti-pattern vì:

| Vấn đề | Hệ quả |
|---|---|
| `kubectl set image` thay đổi cluster mà **không cập nhật git** | Git và cluster drift — không biết cluster đang chạy gì |
| Deployment files hardcode `latest` tag | Không trace được version nào đang chạy |
| GitHub Actions cần kubeconfig full-access | Secret mạnh, blast radius lớn nếu lộ |
| Muốn rollback phải chạy lại workflow cũ | Không có single source of truth |
| Không có approval gate trước khi production | Push lên main là deploy thẳng |

**GitOps nguyên tắc:** Git là source of truth duy nhất. Cluster tự sync về trạng thái được mô tả trong git — không ai `kubectl apply` hay `kubectl set image` thủ công.

---

## Kiến trúc GitOps với ArgoCD

```
┌─────────────────────────────────────────────────────────────┐
│                        GitHub                                │
│                                                              │
│  app code repo          infra/manifests repo (hoặc folder)  │
│  (backend/**)           (infra/k8s/**)                      │
│       │                        │                            │
│  CI: build image          CD: cập nhật image tag            │
│  push ghcr.io             commit vào git                    │
└──────────────────────────────┬──────────────────────────────┘
                               │ git pull (mỗi 3 phút)
                               ▼
                    ┌─────────────────────┐
                    │      ArgoCD         │
                    │  (trong cluster)    │
                    │                     │
                    │  detect diff?       │
                    │  → sync tự động     │
                    └──────────┬──────────┘
                               │ kubectl apply
                               ▼
                    ┌─────────────────────┐
                    │   K8s cluster       │
                    │   namespace:        │
                    │   robomart          │
                    └─────────────────────┘
```

GitHub Actions **không còn chạm vào cluster**. Nó chỉ:
1. Build image → push GHCR
2. Cập nhật image tag trong file yaml → commit git

ArgoCD tự detect git thay đổi → sync vào cluster.

---

## Kustomize — cần thêm vào trước khi dùng ArgoCD

Hiện tại deployment files hardcode `image: ghcr.io/robomart/product-service:latest`. Cần Kustomize để cập nhật image tag qua git mà không sửa thẳng deployment.yml.

### Cấu trúc thư mục sau khi thêm Kustomize

```
infra/k8s/
├── base/
│   ├── namespace.yml
│   ├── configmap.yml
│   ├── secrets-template.yml
│   └── kustomization.yaml          ← MỚI
└── overlays/
    └── production/
        ├── kustomization.yaml      ← MỚI — image tags được cập nhật ở đây
        └── patches/
            └── replicas.yaml       ← tuỳ chọn: override replica count per env
```

**`infra/k8s/base/kustomization.yaml`:**
```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - namespace.yml
  - configmap.yml
  - ../services/api-gateway/deployment.yml
  - ../services/api-gateway/service.yml
  - ../services/api-gateway/hpa.yml
  - ../services/product-service/deployment.yml
  - ../services/product-service/service.yml
  - ../services/product-service/hpa.yml
  - ../services/cart-service/deployment.yml
  - ../services/cart-service/service.yml
  - ../services/cart-service/hpa.yml
  - ../services/order-service/deployment.yml
  - ../services/order-service/service.yml
  - ../services/order-service/hpa.yml
  - ../services/inventory-service/deployment.yml
  - ../services/inventory-service/service.yml
  - ../services/inventory-service/hpa.yml
  - ../services/payment-service/deployment.yml
  - ../services/payment-service/service.yml
  - ../services/payment-service/hpa.yml
  - ../services/notification-service/deployment.yml
  - ../services/notification-service/service.yml
  - ../services/notification-service/hpa.yml
```

**`infra/k8s/overlays/production/kustomization.yaml`:**
```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: robomart
resources:
  - ../../base

# CI cập nhật block này sau mỗi build thành công
images:
  - name: ghcr.io/robomart/api-gateway
    newTag: main-a1b2c3d          # ← CI commit SHA, được CI tự cập nhật
  - name: ghcr.io/robomart/product-service
    newTag: main-a1b2c3d
  - name: ghcr.io/robomart/cart-service
    newTag: main-a1b2c3d
  - name: ghcr.io/robomart/order-service
    newTag: main-a1b2c3d
  - name: ghcr.io/robomart/inventory-service
    newTag: main-a1b2c3d
  - name: ghcr.io/robomart/payment-service
    newTag: main-a1b2c3d
  - name: ghcr.io/robomart/notification-service
    newTag: main-a1b2c3d
```

---

## Cập nhật CD workflow

Bỏ toàn bộ `deploy-k8s` job. Thay bằng job cập nhật image tag trong git:

```yaml
# cd-deploy.yml — thay job deploy-k8s bằng:

  update-image-tags:
    name: Update Image Tags in Git
    needs: build-and-push
    runs-on: ubuntu-24.04
    if: github.ref == 'refs/heads/main'
    permissions:
      contents: write   # cần để commit vào repo

    steps:
      - name: Checkout
        uses: actions/checkout@v4
        with:
          token: ${{ secrets.GITHUB_TOKEN }}

      - name: Install kustomize
        run: |
          curl -sL "https://raw.githubusercontent.com/kubernetes-sigs/kustomize/master/hack/install_kustomize.sh" | bash
          sudo mv kustomize /usr/local/bin/

      - name: Update image tags
        run: |
          SHORT_SHA="${GITHUB_SHA:0:7}"
          IMAGE_TAG="main-${SHORT_SHA}"
          REGISTRY="ghcr.io/${{ github.repository_owner }}/robomart"

          cd infra/k8s/overlays/production

          for SERVICE in api-gateway product-service cart-service order-service \
                         inventory-service payment-service notification-service; do
            kustomize edit set image ${REGISTRY}/${SERVICE}:${IMAGE_TAG}
          done

      - name: Commit and push
        run: |
          SHORT_SHA="${GITHUB_SHA:0:7}"
          git config user.name "github-actions[bot]"
          git config user.email "github-actions[bot]@users.noreply.github.com"
          git add infra/k8s/overlays/production/kustomization.yaml
          git commit -m "chore: bump images to main-${SHORT_SHA} [skip ci]"
          git push
```

> `[skip ci]` trong commit message ngăn CI chạy lại vòng lặp vô tận khi bot commit.

**Lúc này `KUBECONFIG` secret không cần nữa** — GitHub Actions không còn kết nối trực tiếp vào cluster.

---

## Cài đặt ArgoCD

### Bước 1 — Cài ArgoCD vào cluster

```bash
kubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml

# Chờ pods ready
kubectl wait --for=condition=available deployment/argocd-server -n argocd --timeout=120s
```

### Bước 2 — Truy cập ArgoCD UI

```bash
# Port-forward tạm để lấy password
kubectl port-forward svc/argocd-server -n argocd 8090:443

# Lấy initial admin password
kubectl get secret argocd-initial-admin-secret -n argocd \
  -o jsonpath="{.data.password}" | base64 -d
```

Truy cập `https://localhost:8090`, login với `admin` + password trên. Đổi password ngay sau khi login lần đầu.

### Bước 3 — Tạo ArgoCD Application manifest

Tạo file này commit vào repo (ArgoCD quản lý chính nó theo GitOps):

**`infra/k8s/argocd/robomart-app.yaml`:**
```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: robomart-production
  namespace: argocd
  # Tự xóa resources khi Application bị xóa
  finalizers:
    - resources-finalizer.argocd.argoproj.io
spec:
  project: default

  source:
    repoURL: https://github.com/tuanpm2026/robo-mart  # đổi thành repo thật
    targetRevision: main
    path: infra/k8s/overlays/production  # Kustomize overlay

  destination:
    server: https://kubernetes.default.svc  # cluster hiện tại (in-cluster)
    namespace: robomart

  syncPolicy:
    automated:
      prune: true      # xóa resources không còn trong git
      selfHeal: true   # revert nếu ai đó sửa cluster tay
    syncOptions:
      - CreateNamespace=true
      - PrunePropagationPolicy=foreground
    retry:
      limit: 3
      backoff:
        duration: 5s
        factor: 2
        maxDuration: 3m
```

Apply vào cluster:
```bash
kubectl apply -f infra/k8s/argocd/robomart-app.yaml
```

### Bước 4 — Cấp quyền đọc repo private

Nếu repo private, ArgoCD cần credentials để pull:

```bash
# Dùng GitHub Personal Access Token (read:repo)
argocd repo add https://github.com/tuanpm2026/robo-mart \
  --username tuanpm2026 \
  --password <GITHUB_PAT>
```

---

## Luồng hoàn chỉnh sau khi setup ArgoCD

```
Developer push code lên main
        │
        ▼
  Backend CI chạy
  (Build → Test → Contract → OpenAPI)
        │ pass
        ▼
  CD: Build & Push Docker images
  → ghcr.io/{owner}/robomart/{service}:main-{sha}
        │
        ▼
  CD: kustomize edit set image
  → commit vào infra/k8s/overlays/production/kustomization.yaml
  → push (commit message: "chore: bump images to main-{sha} [skip ci]")
        │
        ▼
  ArgoCD phát hiện git thay đổi (poll mỗi 3 phút)
  hoặc nhận webhook ngay lập tức
        │
        ▼
  ArgoCD diff: kustomization.yaml mới vs cluster hiện tại
        │ có diff
        ▼
  ArgoCD sync: kubectl apply (Kustomize render → apply)
  Rolling update tự động, selfHeal=true
        │
        ▼
  ArgoCD UI hiển thị trạng thái Healthy / Synced
```

---

## So sánh trước và sau

| | Trước (kubectl push) | Sau (ArgoCD GitOps) |
|---|---|---|
| Source of truth | Cluster state | Git |
| Deploy trigger | GitHub Actions chạy kubectl | ArgoCD tự poll git |
| Rollback | Chạy lại workflow cũ | `git revert` + push (hoặc 1 click UI) |
| Cluster access từ CI | KUBECONFIG full-access | Không cần |
| Audit trail | GitHub Actions log | Git history + ArgoCD history |
| Drift detection | Không có | selfHeal=true tự revert |
| Image tag trong git | Không (hardcode latest) | Có (commit SHA rõ ràng) |
| Multi-env (staging/prod) | Phải sửa workflow | Thêm overlay mới |

---

## Webhook (tuỳ chọn — sync ngay thay vì đợi 3 phút)

Mặc định ArgoCD poll git mỗi 3 phút. Để sync ngay khi có commit:

**Trong ArgoCD UI:** Settings → Repositories → webhook URL dạng:
`https://<argocd-server>/api/webhook`

**Thêm vào GitHub repo:** Settings → Webhooks → Add webhook:
- Payload URL: `https://<argocd-server>/api/webhook`
- Content type: `application/json`
- Secret: tạo random string, set vào ArgoCD secret `argocd-secret` key `webhook.github.secret`
- Events: `push`

---

## Checklist migration

```
□ Thêm infra/k8s/base/kustomization.yaml
□ Tạo infra/k8s/overlays/production/kustomization.yaml (với image tags hiện tại)
□ Tạo infra/k8s/argocd/robomart-app.yaml
□ Cài ArgoCD vào cluster
□ Apply robomart-app.yaml → ArgoCD nhận quản lý
□ Cập nhật cd-deploy.yml: bỏ job deploy-k8s, thêm job update-image-tags
□ Verify: push code → CI build → git commit tag → ArgoCD sync → cluster updated
□ Xóa secret KUBECONFIG khỏi GitHub (không cần nữa)
□ Cấu hình webhook nếu muốn sync realtime
```
