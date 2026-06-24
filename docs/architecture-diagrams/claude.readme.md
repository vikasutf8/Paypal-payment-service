# PayFlow — Distributed Payments Platform (PayPal/Stripe/Razorpay-style Clone)
### System Design & Implementation Roadmap

---

## 0. First, a Correction to Your List

Before the architecture — a few items on your list aren't actually *microservices*. Calling them services will hurt you later (extra network hops, extra deployments, extra failure points for zero benefit). Reclassifying:

| Your Item | What It Actually Is | Where It Lives |
|---|---|---|
| ms9 — globalError/Dto Service | A **shared library** (`common-core` JAR) | Imported as a Maven/Gradle dependency by every service |
| ms14 — Resilience Service | A **per-service library config** (Resilience4j) | Embedded in every service that makes outbound calls (esp. API Gateway, Transaction, Wallet) |
| ms15 — Distributed Tracing Service | **Infrastructure**, not application code | OTel Collector + Grafana stack, deployed once, all services just export to it |
| ms16 — Distributed Scheduler | Could be a real service *or* a library (ShedLock) depending on scale | See §6 |

Building these as standalone Spring Boot apps with their own DB/deployment is the #1 way these portfolio projects become unmanageable. Treat them as **cross-cutting concerns**, not nodes in your service graph.

---

## 1. Tech Stack (Current, Production-Grade)

| Layer | Choice | Notes |
|---|---|---|
| Language | Java 21 (LTS) | Virtual threads (Project Loom) — huge win for I/O-bound services like yours |
| Framework | Spring Boot 3.3.x / 3.4.x | Spring 6, Jakarta EE namespace |
| Cloud | Spring Cloud 2023.0.x ("Leyden") | Compatible with Boot 3.3.x |
| Gateway | Spring Cloud Gateway (WebFlux, reactive) | Not Zuul — it's EOL |
| Discovery | Netflix Eureka (Spring Cloud Netflix) | Or Consul if you want infra-agnostic discovery + KV config in one |
| Config | Spring Cloud Config Server (Git-backed) | Pair with Spring Cloud Bus (Kafka) for refresh propagation |
| Security | Spring Security 6 + OAuth2 Resource Server/Client | OIDC via Keycloak (self-hosted) or Auth0 |
| Messaging | Apache Kafka (+ Schema Registry, Avro/Protobuf) | Debezium for CDC-based outbox |
| Resilience | Resilience4j (native Spring Boot 3 integration) | Sleuth is dead — don't use it |
| Tracing | **Micrometer Tracing** + OpenTelemetry | Sleuth was replaced by Micrometer Tracing in Boot 3 |
| Metrics | Micrometer → Prometheus → Grafana | |
| Logs | Fluent Bit/Logstash → Elasticsearch → Kibana (EFK/ELK) | |
| DB (financial) | PostgreSQL per service | ACID required for wallet/ledger/transaction |
| Cache/Locks | Redis | Idempotency keys, distributed locks, rate-limit counters |
| Scheduling | ShedLock (simplest) or Quartz-with-JDBCStore (if you need a UI/admin) | |

---

## 2. Reorganized Service Map

### A. Platform / Edge Layer
1. **Config Server** — centralized config, Git-backed, refreshed via Spring Cloud Bus
2. **Service Registry** (Eureka) — multi-instance discovery
3. **API Gateway** — single entry point; auth delegation, rate limiting, retry, webhook routing, bulk endpoint handling (details in §5)
4. **Auth Service** — JWT issuance, OAuth2/OIDC (Google/GitHub), mobile OTP, TOTP authenticator app (Google Authenticator-compatible) support

### B. Core Business Services
5. **User Service** — profile, KYC status, preferences
6. **Wallet Service** — balance, holds/freezes, debit/credit operations (idempotent)
7. **Transaction Service** — orchestrates payment lifecycle, owns the SAGA
8. **Ledger Service** — immutable double-entry bookkeeping (the source of financial truth — **never** let other services write directly to it)
9. **Notification Service** — email/SMS/push, consumes events async
10. **Reward Service** — cashback/points, consumes transaction-completed events

### C. Later / Optional
11. Expense Split Service
12. Group/Chat Service

### D. Shared Library (not a deployed service)
- `common-core`: shared DTOs, global exception handler (`@ControllerAdvice` base classes), standard error response shape, common validation annotations

### E. Infrastructure (deployed once, shared by all)
- Kafka cluster + Schema Registry + Kafka Connect (Debezium)
- Redis cluster
- OTel Collector → Tempo/Jaeger (traces), Prometheus (metrics), Loki/ELK (logs) → Grafana (single pane of glass)

---

## 3. Request Flow (Sync Path)

```
Client
  │
  ▼
API Gateway  ──(validates JWT signature/expiry locally, no network call)──┐
  │                                                                       │
  ▼                                                                      │
Routes to target service ─── each service trusts the gateway-verified ───┘
                              token (claims forwarded as headers)
```

**Important correction to your plan:** don't have the Gateway call the Auth Service synchronously on every request — that makes Auth Service a single point of failure and a latency bottleneck for *every* call in the system. Instead:
- Auth Service issues a JWT signed with its private key (RS256)
- Gateway validates the signature **locally** using the public key (cached, fetched via JWKS endpoint) — no network call per request
- Auth Service is only called for login, refresh, and OTP/TOTP verification — not for every routed request

---

## 4. Async Path — Event-Driven Core (Kafka + SAGA + Outbox)

### 4.1 Why orchestration-based SAGA for payments
Choreography (services reacting to each other's events with no coordinator) is elegant but hard to debug and hard to reason about for money movement, where you need a clear, auditable state machine. Use:

- **Orchestration SAGA** for the core payment flow (Transaction Service is the orchestrator)
- **Choreography** for side-effects that don't need rollback (Notification, Reward)

### 4.2 Example: "Transfer Money" SAGA

```
1. Transaction Service: creates Transaction record (status=INITIATED), writes
   "TransactionInitiated" event to its own outbox table (same DB transaction).
2. Outbox poller/Debezium publishes event to Kafka topic: transaction.initiated
3. Wallet Service consumes → attempts debit (idempotent, keyed by transactionId)
     → success: publish wallet.debited
     → failure (insufficient funds): publish wallet.debit.failed
4. Transaction Service consumes wallet.debited
     → calls Wallet Service to credit receiver
     → success: publish wallet.credited
     → failure: triggers COMPENSATION → publish wallet.debit.reversal
5. Ledger Service consumes wallet.credited → writes immutable double-entry
   ledger rows → publishes ledger.recorded
6. Transaction Service marks transaction COMPLETED on ledger.recorded
7. Notification Service & Reward Service independently consume
   transaction.completed (choreography — fire and forget, no SAGA needed)
```

Compensating actions (debit reversal, hold release) must themselves be idempotent and themselves go through the outbox — a compensation that fails silently is worse than the original failure.

### 4.3 Outbox Pattern — Implementation Detail

The core problem it solves: **you cannot atomically write to your DB and publish to Kafka** (two different systems = no shared transaction). The outbox pattern fixes this:

```sql
-- Same transaction as the business write
BEGIN;
  INSERT INTO wallet (...) ...;
  INSERT INTO outbox_event (id, aggregate_id, event_type, payload, created_at, published)
    VALUES (...);
COMMIT;
```

Two ways to publish from the outbox table to Kafka:
- **Polling publisher** (simple): a scheduled job reads unpublished rows, publishes, marks published. Easy to build, slight latency, needs the scheduler to be lock-safe across instances (→ ShedLock).
- **Debezium CDC** (production-grade): tails the DB's write-ahead log, publishes outbox inserts to Kafka automatically, near-zero latency, no polling load on the DB. This is what real fintech systems use — recommend this for the "advanced" version of your project.

### 4.4 Idempotency
Every consumer must dedupe on a message/idempotency key (store processed message IDs in Redis or a `processed_events` table) — Kafka's at-least-once delivery means **every consumer will see duplicates eventually.**

---

## 5. API Gateway Patterns (Your List, Made Concrete)

| Pattern | Implementation |
|---|---|
| **Rate Limiting** | Spring Cloud Gateway's `RequestRateLimiter` filter backed by Redis (token bucket). Key by userId + IP. |
| **Retry** | Gateway-level retry only for **idempotent** GET/read calls. For payment-mutating calls, retries must carry an `Idempotency-Key` header that the downstream service dedupes on — never blindly retry a debit. |
| **Circuit Breaker** | Resilience4j `CircuitBreaker` wrapping each downstream route; fallback returns a clear "service degraded" response, not a generic 500. |
| **Webhook** | A dedicated outbound webhook delivery mechanism (can live inside Notification Service): signs payloads (HMAC), retries with exponential backoff, dead-letters after N attempts, exposes a "redeliver" endpoint. |
| **Bulk** | Bulk endpoints accept a batch, return a `batchId` immediately (202 Accepted), process async via Kafka, client polls a status endpoint or receives a webhook on completion — never make the client wait synchronously on a large batch. |

---

## 6. Distributed Scheduler — Recommendation

Given your earlier production cron issue (overlapping executions, sequential DB calls under load), I'd steer you toward **ShedLock** over building a full scheduler microservice unless you specifically want the systems-design challenge of building one:

- ShedLock + `@Scheduled` + Redis/DB lock provider = guarantees only one instance runs a given job at a time across all replicas, with near-zero extra infra.
- Only build a dedicated Scheduler Service (Quartz-backed, with persistent job store) if you want a recruiter-visible "I built a distributed job scheduler" feature — it's a fair thing to showcase, just know it's solving a problem ShedLock solves more simply.

---

## 7. Observability Stack (ms15, Properly Scoped)

This is infrastructure you deploy once, not a service you write business logic for:

```
All services ──export──> OTel Collector ──┬──> Prometheus ──> Grafana (metrics)
                                            ├──> Tempo/Jaeger ──> Grafana (traces)
                                            └──> Loki/Elasticsearch ──> Grafana/Kibana (logs)
```

Spring Boot 3 wiring: `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` + Actuator. Every service gets a trace ID propagated automatically through Kafka headers and HTTP headers — this is what lets you follow one transaction across all 8+ services it touches.

---

## 8. Database Strategy

- **Database-per-service**, no shared schemas (this is non-negotiable for a microservices project — it's the whole point)
- PostgreSQL for: Wallet, Transaction, Ledger, User (strong consistency, financial correctness)
- Ledger table design: **append-only**, no UPDATE/DELETE ever — corrections are new compensating rows, never edits. This is what makes it auditable.
- Consider Postgres logical replication / read replicas for Ledger if you want to show read-scaling

---

## 9. Suggested Build Order (Phased — Don't Build All 16 at Once)

**Phase 1 — Skeleton (get something running end to end)**
Config Server → Service Registry → API Gateway → Auth Service → User Service → Wallet Service (sync REST only, no Kafka yet)

**Phase 2 — Make it a real payment flow**
Transaction Service + Ledger Service, wired with Kafka + outbox pattern (polling publisher version first — simplest)

**Phase 3 — Make it resilient**
Resilience4j into Gateway + all inter-service calls; idempotency keys on Wallet/Transaction

**Phase 4 — Make it observable**
OTel + Prometheus + Grafana + Loki stack; trace one transaction end-to-end through Grafana

**Phase 5 — Make it production-grade**
Swap polling outbox → Debezium CDC; add Notification + Reward Service (choreography); add Gateway patterns (rate limit, webhook, bulk)

**Phase 6 — Optional/portfolio extras**
Expense Split, Chat/Grouping, dedicated Scheduler Service if you want it over ShedLock

This order means you have a *demoable, working system* after Phase 2 — important for both interview talking points and not losing motivation 12 services in.

---

## 10. Repo Structure Suggestion

```
payflow/
├── common-core/              # shared lib: DTOs, exception handling, error contracts
├── config-server/
├── service-registry/
├── api-gateway/
├── auth-service/
├── user-service/
├── wallet-service/
├── transaction-service/
├── ledger-service/
├── notification-service/
├── reward-service/
├── infra/
│   ├── docker-compose.yml    # Kafka, Postgres x N, Redis, OTel collector, Grafana stack
│   └── k8s/                  # later: Helm charts per service
└── docs/
    └── payment-platform-design.md
```

Each service gets its own `pom.xml`/`build.gradle`, its own Postgres instance (own container), and depends on `common-core` as a published/local Maven artifact.