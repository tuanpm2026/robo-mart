# Robo-Mart

Nền tảng thương mại điện tử microservices: Java 21 / Spring Boot, Vue 3, PostgreSQL, Redis, Elasticsearch, Kafka và Keycloak.

## Tài liệu bắt đầu

**[Luồng development và hoạt động toàn hệ thống](docs/development-system-flow.md)** — bản tổng hợp tiếng Việt, đối chiếu mã nguồn ngày 24/10/2026:

- Kiến trúc, service, dữ liệu và giao tiếp REST/GraphQL/gRPC/Kafka.
- Dựng môi trường local, quy trình phát triển và kiểm thử.
- Đăng nhập, giỏ hàng, checkout, Saga, hủy đơn và hoàn tiền.
- Notification, dashboard realtime, đối soát, CI/CD và vận hành.
- Các phần mô phỏng, giới hạn và lệch cấu hình hiện tại.

## Tài liệu chuyên sâu

- [Yêu cầu sản phẩm](_bmad-output/planning-artifacts/prd.md)
- [Thiết kế kiến trúc](_bmad-output/planning-artifacts/architecture.md)
- [Epics và stories](_bmad-output/planning-artifacts/epics.md)
- [CI/CD guide](docs/cicd-guide.md)
- [ArgoCD và GitOps](docs/argocd-gitops-guide.md)
- [Dựng Kubernetes từng bước](docs/k8s-setup-step-by-step.md)
- [Performance test với k6](backend/k6/README.md)

Các tài liệu thiết kế/hướng dẫn cũ có thể khác implementation hiện tại. Xem bản tổng hợp để biết các khác biệt đã đối chiếu; cấu hình và mã nguồn trong checkout là căn cứ cho hành vi thực tế.
