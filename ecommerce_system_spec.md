# ECOMMERCE SIDE PROJECT – SYSTEM SPEC

## 1. Objective
Build a production-grade Ecommerce backend system using:
- Java (Spring Boot)
- Microservices architecture
- Distributed system patterns

Goals:
- Practice real-world backend engineering
- Handle distributed challenges (consistency, concurrency, scaling)
- Reach senior-level system design
- Prepare for remote job (3000$+)

---

## 2. Constraints

- Solo developer
- ~4 hours/day
- Hardware:
  - Laptop (dev)
  - HP Z420 server (dev cluster)

---

## 3. Architecture

Microservices:

- API Gateway
- Product Service
- Cart Service
- Order Service
- Inventory Service
- Payment Service (mock)
- Notification Service

Communication:
- Sync: REST
- Async: Kafka

---

## 4. Core Features

1. Product + Search (Elasticsearch)
2. Cart (Redis)
3. Order (state machine)
4. Inventory (avoid oversell)
5. Payment (mock + retry + idempotency)
6. Event-driven flow (Kafka)
7. Notification

---

## 5. Key Technical Problems

- Race condition
- Distributed consistency
- Idempotency
- Retry mechanism
- Eventual consistency

---

## 6. Dev Workflow

Laptop:
- Code + test nhanh

Server (HP Z420):
- K3s cluster
- Full system test

Flow:
Code → Build → Deploy → Test

---

## 7. CI/CD

- GitHub
- GitHub Actions

Pipeline:
- Build
- Test
- Dockerize
- Deploy (staging → production)

---

## 8. Environment Strategy

- Dev: local + server
- Staging: VPS
- Production: AWS (EKS or ECS)

---

## 9. Tools

- Spring Boot
- Docker
- Kubernetes (K3s)
- Kafka
- Redis
- PostgreSQL
- Elasticsearch

---

## 10. Roadmap (6–8 weeks)

Week 1–2:
- Product + Cart

Week 3–4:
- Order + Inventory

Week 5–6:
- Kafka + Payment

Week 7–8:
- CI/CD + optimize

---

## Generated at: 2026-03-26 09:34:31.409703
