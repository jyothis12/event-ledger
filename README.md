# Event Ledger

A distributed financial transaction processing system composed of two microservices that handle out-of-order and duplicate event delivery gracefully.

## Architecture Overview

```
Browser / Client ──────→  Event Gateway API (port 8080)
                                    │
                                    │ REST (sync) + Trace propagation
                                    ▼
                          Account Service (port 8081)
```

### Event Gateway (`:8080`)
The public-facing entry point. Responsibilities:
- Validates incoming transaction events
- Enforces idempotency (duplicate `eventId` returns the original event, no re-processing)
- Persists events to its own embedded H2 database
- Calls the Account Service to apply transactions
- Propagates distributed trace IDs via `X-Trace-Id` header
- Implements circuit breaker (Resilience4j) on Account Service calls
- `GET /events/{id}` and `GET /events?account=...` continue to work even when Account Service is down

### Account Service (`:8081`)
Internal service, not exposed to external clients. Responsibilities:
- Manages account balances and transaction history in its own embedded H2 database
- Applies CREDIT/DEBIT transactions idempotently
- Always recomputes balance from all stored transactions — handles out-of-order arrivals correctly
- Accepts and logs the `X-Trace-Id` header for trace correlation

---

## Prerequisites

- **Java 17+**
- **Maven 3.8+**
- **Docker + Docker Compose** (for containerized run)

---

## Running with Docker Compose (Recommended)

```bash
# From the project root
docker-compose up --build
```

Both services will start. The Gateway waits for the Account Service to be healthy before accepting traffic.

- Event Gateway: http://localhost:8080
- Account Service: http://localhost:8081

To stop:
```bash
docker-compose down
```

---

## Running Manually (Without Docker)

Open two terminals.

**Terminal 1 — Account Service (start first)**
```bash
cd account-service
mvn spring-boot:run
# Starts on port 8081
```

**Terminal 2 — Event Gateway**
```bash
cd event-gateway
mvn spring-boot:run
# Starts on port 8080
```

---

## Running the Tests

Each service has its own test suite with an embedded H2 database — no external dependencies needed.

```bash
# Test Event Gateway
cd event-gateway
mvn test

# Test Account Service
cd account-service
mvn test
```

Tests cover:
- Input validation (missing fields, negative amounts, invalid types)
- Idempotency (duplicate `eventId` returns original without re-processing)
- Out-of-order events (correct chronological listing and balance regardless of arrival order)
- Resiliency (Account Service failure returns 503, not a hang)
- Graceful degradation (GET endpoints work even when Account Service is down)
- Trace propagation (client-supplied `X-Trace-Id` flows through to Account Service)
- Health checks

---

## API Reference

### Event Gateway (`:8080`)

#### Submit an event
```
POST /events
Content-Type: application/json

{
  "eventId": "evt-001",
  "accountId": "acct-123",
  "type": "CREDIT",
  "amount": 150.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T14:02:11Z",
  "metadata": { "source": "mainframe-batch", "batchId": "B-9042" }
}
```
- `201 Created` — new event processed
- `200 OK` — duplicate `eventId`, returns original event
- `400 Bad Request` — validation failure
- `503 Service Unavailable` — Account Service unreachable

#### Get event by ID
```
GET /events/{id}
```
Works even when Account Service is down.

#### List events for an account
```
GET /events?account={accountId}
```
Returns events ordered by `eventTimestamp` ascending. Works even when Account Service is down.

#### Health check
```
GET /health
```

### Account Service (`:8081`)

#### Apply a transaction
```
POST /accounts/{accountId}/transactions
```

#### Get balance
```
GET /accounts/{accountId}/balance
```

#### Get account details
```
GET /accounts/{accountId}
```

#### Health check
```
GET /health
```

---

## Resiliency Pattern: Circuit Breaker

I chose **Resilience4j Circuit Breaker** on the Gateway's calls to the Account Service.

**Why:** The circuit breaker is the most appropriate pattern here because it provides both fast failure and automatic recovery. When the Account Service is repeatedly failing, the circuit opens and the Gateway immediately returns a `503` rather than waiting for timeouts to accumulate. This protects the Gateway's thread pool and gives clients a clear, actionable signal. After a configured wait period the circuit transitions to half-open and allows probe requests through — if the Account Service has recovered, traffic resumes automatically.

**Configuration (in `application.properties`):**
| Setting | Value | Meaning |
|---|---|---|
| `slidingWindowSize` | 10 | Evaluate failure rate over last 10 calls |
| `failureRateThreshold` | 50% | Open circuit if ≥50% of calls fail |
| `waitDurationInOpenState` | 10s | Wait before trying again |
| `permittedCallsInHalfOpen` | 3 | Probe requests before fully closing |

I also configured **retry with exponential backoff** (3 attempts, 500ms initial wait, 2× multiplier) for transient errors, and a **5-second timeout** on Account Service calls. The retry runs inside the circuit breaker so repeated failures advance the failure count correctly.

---

## Observability

### Structured Logging
Both services emit JSON-formatted log lines with `timestamp`, `level`, `service`, `traceId`, and `message` on every line. Example:
```json
{"timestamp":"2026-06-29T10:00:00.000","level":"INFO","service":"event-gateway","traceId":"a1b2-...","message":"Processing event eventId=evt-001 accountId=acct-123 type=CREDIT amount=150.00"}
```

### Distributed Tracing
- The Gateway generates a UUID trace ID for each incoming request (or accepts a client-supplied `X-Trace-Id` header).
- The trace ID is stored in MDC and forwarded to the Account Service via `X-Trace-Id`.
- The Account Service reads and logs the same `traceId`. A single client request produces a traceable path across both services.
- The trace ID is returned to the client in the response `X-Trace-Id` header.

### Metrics
Custom Micrometer counters exposed via `/actuator/metrics` and `/actuator/prometheus`:
- `events.received.total` — all events received at the Gateway
- `events.processed.total` — events successfully applied
- `events.duplicate.total` — duplicate events detected
- `events.failed.total` — events that failed Account Service call
- `transactions.applied.total` — transactions applied at Account Service
- `transactions.duplicate.total` — duplicates skipped at Account Service

Circuit breaker state also exposed at `/actuator/circuitbreakers` on the Gateway.

---

## Bonus: Zipkin Trace Visualization

When running via Docker Compose, a Zipkin instance starts automatically alongside the two services.

**Access the Zipkin UI:** http://localhost:9411

Both services export spans to Zipkin using Micrometer Tracing + Brave. Every `POST /events` request produces a trace spanning both the Gateway and Account Service, visible as a waterfall in the Zipkin UI. 100% of requests are sampled (`management.tracing.sampling.probability=1.0`).

To find a trace: submit an event, copy the `X-Trace-Id` from the response header, and paste it into the Zipkin search box under "Trace ID".

---

## Design Decisions

**Balance computation:** The Account Service always recomputes balance from `SUM(CREDITs) - SUM(DEBITs)` over all stored transactions rather than maintaining a running total. This guarantees correctness regardless of event arrival order — a DEBIT that arrives before its corresponding CREDIT still produces the right final balance.

**Idempotency at both layers:** Both the Gateway and the Account Service independently check for duplicate `eventId`. The Gateway short-circuits before calling the Account Service on duplicates, but the Account Service also guards against direct duplicate calls, making the system robust against retries at any layer.

**Separate databases:** Each service uses its own H2 in-memory database with no shared state, satisfying the service boundary requirement. The Gateway's DB holds event records; the Account Service's DB holds transaction records and account balances.

**GET endpoints always work:** `GET /events/{id}` and `GET /events?account=...` read only from the Gateway's own database and have no dependency on the Account Service, so they continue to function correctly when the Account Service is down.
