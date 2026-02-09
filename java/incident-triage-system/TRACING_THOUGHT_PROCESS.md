# How I Decided to Instrument This Distributed Service
### A Q&A on Tracing Strategy for the Incident Triage System

---

## Q: Why did I even need distributed tracing? What problem am I solving?

When a user hits `POST /orders`, three services are involved: order-service validates products by calling product-service, then triggers payment-service. If something goes wrong — a slow response, a 500 error, a timeout — which service caused it?

Without tracing, all you have is three separate log streams. You'd have to manually correlate timestamps across services, guess which request in product-service corresponds to which order, and hope the clocks are synced. That's not debugging — that's archaeology.

Distributed tracing gives you a single `traceId` that follows a request across all three services. One ID, one story, from entry to exit.

---

## Q: How did I decide WHAT to instrument?

I followed the "follow the latency" principle. For any request, latency hides in three places:

1. **Network calls** (HTTP between services) — This is where distributed tracing shines. Every time order-service calls product-service or payment-service, that's a span boundary. These are the most important things to trace because they cross process boundaries.

2. **Database queries** (JPA/Hibernate → H2) — Every `findById()`, `save()`, `findAll()` is a database roundtrip. In production with a real database, these can be the dominant source of latency. A missing index, an N+1 query, a table lock — all invisible without DB spans.

3. **Business logic** (validation, calculation, transformation) — The code between the network calls. Stock validation, total calculation, payment status mapping. Usually fast, but custom spans here tell you WHERE in the code time is being spent.

The priority order matters: **network calls > database > business logic**. Instrument in that order.

---

## Q: What spans does the current setup generate automatically?

With `micrometer-tracing-bridge-otel` on the classpath and Spring Boot auto-configuration, you get these spans for free — zero code required:

**For `POST /orders` with 2 products:**

```
[SERVER] order-service: POST /orders                    ~350ms total
  ├── [CLIENT] order-service → product-service: GET /products/1    ~15ms
  │     └── [SERVER] product-service: GET /products/1              ~10ms
  ├── [CLIENT] order-service → product-service: GET /products/3    ~12ms
  │     └── [SERVER] product-service: GET /products/3              ~8ms
  └── [CLIENT] order-service → payment-service: POST /payments     ~165ms
        └── [SERVER] payment-service: POST /payments               ~155ms
```

**What each span captures:**
- HTTP method and URL
- HTTP status code
- Service name (from `spring.application.name`)
- Duration
- Parent-child relationships (which call triggered which)
- The W3C `traceparent` header propagates the traceId across services

**What's NOT captured yet (gaps):**
- Database queries (no JDBC/JPA spans)
- Business logic (no custom spans for validation, calculation)
- Error details (just status codes, not exception messages)

---

## Q: Why does trace propagation matter so much? What would break without it?

Trace propagation is the mechanism that links spans across services. When order-service calls product-service, it injects a `traceparent` HTTP header:

```
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
                 ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^  ^^^^^^^^^^^^^^^^
                 traceId (shared across all)       parentSpanId
```

Product-service reads this header and continues the same trace. Without propagation, you'd get three isolated traces — one per service — with no way to connect them.

**The critical implementation detail:** RestClient beans MUST be built via Spring's auto-configured `RestClient.Builder`, not `RestClient.create()`. The Builder has Micrometer's `ObservationRestClientCustomizer` applied, which handles the `traceparent` injection. If you bypass the Builder, propagation silently breaks and you won't know until you look at your traces and see disconnected spans.

---

## Q: How would tracing help me debug a slow order?

**Scenario: A customer reports that placing an order took 8 seconds.**

Without tracing, you check order-service logs and see the request took 8 seconds. But why? Was it product-service? Payment? The database? You don't know.

With tracing, you pull up the trace by traceId and see:

```
[order-service] POST /orders                           8,200ms
  ├── GET /products/1                                     15ms  ✓ fast
  ├── GET /products/2                                     12ms  ✓ fast
  ├── GET /products/3                                  5,100ms  ← HERE
  │     └── [product-service] GET /products/3          5,095ms
  │           └── [DB] SELECT * FROM products WHERE id=3  5,080ms  ← ROOT CAUSE
  └── POST /payments                                     165ms  ✓ fast
```

**Root cause:** Product ID 3 triggered a slow database query — maybe a table lock, a missing index, or a cold cache. You found it in seconds, not hours.

**What you need for this:** HTTP spans (already have) + DB spans (need to add).

---

## Q: How would tracing help me debug a failed payment?

**Scenario: Orders are intermittently failing with "FAILED" status.**

With tracing, you filter for traces where the payment span has an error:

```
[order-service] POST /orders                           350ms  ERROR
  ├── GET /products/1                                   15ms  ✓
  ├── GET /products/2                                   12ms  ✓
  └── POST /payments                                    50ms  ERROR
        └── [payment-service] POST /payments            45ms  ERROR
              └── span.attributes:
                    error: true
                    error.message: "Connection refused: payment-gateway.example.com:443"
                    http.status_code: 500
```

**Root cause:** The payment service's upstream payment gateway is down. The error span tells you it's not YOUR code — it's the external dependency.

**What you need for this:** Error recording on spans (automatic with Spring Boot) + meaningful error messages (need custom exception handling).

---

## Q: How would tracing help with an N+1 query problem?

**Scenario: `GET /products` is slow when there are 1000 products.**

Without DB tracing, you see one server span: `GET /products` taking 3 seconds. With DB tracing enabled, you'd see:

```
[product-service] GET /products                        3,200ms
  └── [DB] SELECT * FROM products                         25ms  ✓ fast
  └── [DB] SELECT * FROM categories WHERE id=1             2ms  × 1000
  └── [DB] SELECT * FROM categories WHERE id=2             2ms  × 1000
  └── [DB] SELECT * FROM categories WHERE id=3             2ms  × 1000
  ... (1000 individual queries)
```

**Root cause:** Classic N+1 — Hibernate is lazy-loading a relationship one row at a time. The fix is a `JOIN FETCH` or `@EntityGraph`. Without DB spans, you'd just see "GET /products is slow" with no explanation.

**What you need for this:** JDBC/JPA auto-instrumentation (not yet added).

---

## Q: How would tracing help with a cascading failure?

**Scenario: product-service goes down. What happens to orders?**

```
[order-service] POST /orders                           30,015ms  ERROR
  ├── GET /products/1                                  30,005ms  ERROR (TIMEOUT)
  │     └── error: "I/O error on GET request: Connection timed out"
  │
  (never reaches payment-service)
```

**What the trace reveals:**
1. Order-service waited 30 seconds for product-service (the default RestClient timeout)
2. Payment-service was never called — the failure cascaded
3. The order is stuck in CREATED status in the database

**Production implications this reveals:**
- You need a timeout on RestClient (e.g., 5 seconds, not 30)
- You need a circuit breaker (Resilience4j) to fail fast
- You need to handle partial failures (order created but payment never attempted)

**What you need for this:** HTTP client spans (already have) + timeout configuration + error span attributes.

---

## Q: How would tracing help with a race condition?

**Scenario: Two customers order the last item simultaneously. Both succeed, but there's only 1 in stock.**

```
Trace A:                                               Trace B:
[order-service] POST /orders                           [order-service] POST /orders
  ├── GET /products/5                                    ├── GET /products/5
  │   stockQuantity: 1  ← both see 1                    │   stockQuantity: 1
  ├── (validates: 1 >= 1 ✓)                              ├── (validates: 1 >= 1 ✓)
  ├── [DB] INSERT INTO orders                            ├── [DB] INSERT INTO orders
  └── POST /payments ✓                                   └── POST /payments ✓
```

**What traces show:** Both traces completed successfully, both saw `stockQuantity: 1`, both passed validation. The stock check and the order creation aren't atomic across services.

**What you need for this:** Custom span attributes recording the `stockQuantity` value seen at validation time. Without custom spans, you'd see two successful traces and have no idea why you oversold.

**Production fix:** Either decrement stock atomically in product-service (with optimistic locking) or use a distributed lock.

---

## Q: How would tracing help with debugging latency percentiles (p99)?

**Scenario: Average order creation is 300ms, but p99 is 4 seconds. Why?**

With enough traces, you can filter for traces above 2 seconds and look for patterns:

```
Slow traces pattern:
  - 80% of slow traces have payment-service spans > 3 seconds
  - These all happen between 2:00-2:05 AM
  - Payment-service DB shows: [DB] INSERT INTO payments  3,100ms

Normal traces:
  - payment-service DB: [DB] INSERT INTO payments  5ms
```

**Root cause:** A nightly batch job runs at 2 AM and locks the payments table. The INSERT blocks until the batch releases the lock.

**What you need for this:** DB spans (not yet added) + a trace backend that supports duration-based filtering (Jaeger, Tempo).

---

## Q: Why do I have a 150ms sleep in payment-service?

This is intentional for tracing demos. In a real system, payment processing involves:
- Calling an external payment gateway (Stripe, PayPal)
- Fraud detection checks
- Idempotency verification

The 150ms simulates this latency so that when you view the trace waterfall in Jaeger, the payment span is visibly wider than the product spans. This makes the trace visually informative rather than a flat line of sub-millisecond spans.

In production, you'd replace the sleep with actual payment gateway calls, and those would naturally show up as child spans.

---

## Q: How does log correlation work with tracing?

Every log line includes `[service-name, traceId, spanId]`:

```
[product-service, abc123def456, span1] Fetching product 1
[order-service,   abc123def456, span2] Creating order with 2 items
[payment-service, abc123def456, span3] Processing payment for order 1
```

The `traceId` is identical across all three services. This means:
- You can `grep abc123def456` across all service logs and see the complete story
- Log aggregation tools (ELK, Loki) can link logs to traces
- **For your RAG pipeline:** The traceId becomes the join key between logs and traces

**Current gap:** Logs are plain text. For a RAG pipeline, you'll want JSON structured logs so you can parse fields programmatically:
```json
{
  "timestamp": "2026-02-08T09:14:35.655",
  "service": "order-service",
  "traceId": "abc123def456",
  "spanId": "span2",
  "level": "INFO",
  "message": "Creating order with 2 items",
  "orderItems": 2
}
```

---

## Q: What's still missing for Week 1 completion?

| Item | Status | What's Needed |
|------|--------|---------------|
| HTTP auto-instrumentation | ✅ Done | Micrometer + RestClient.Builder handles this |
| Database query spans | ❌ Missing | Need JDBC/JPA instrumentation dependency |
| Custom business spans | ❌ Missing | Need `@Observed` on key methods or manual Span API |
| Structured JSON logging | ❌ Missing | Need logstash-logback-encoder + logback-spring.xml |
| OTLP export endpoint | ❌ Missing | Need Jaeger/Tempo + endpoint config in application.yml |
| Trace correlation in logs | ✅ Done | traceId/spanId in log pattern |

---

## Q: What custom spans would I add and why?

If I were adding custom spans (next step after this doc), I'd instrument these specific operations:

**product-service:**
- `validate-product-exists` — distinguish "product lookup" from "product not found" errors
- `check-stock-availability` — when stock checks become complex (reserved stock, warehouse allocation)

**order-service:**
- `validate-order-items` — the loop that calls product-service for each item. Wrapping the entire loop shows total validation time vs individual product lookups
- `calculate-order-total` — usually fast, but custom pricing logic can be surprisingly slow
- `persist-order` — the JPA save. Separate from the DB span to show ORM overhead vs raw SQL time

**payment-service:**
- `process-payment` — the core business operation (currently the 150ms sleep)
- `fraud-check` — when fraud detection is added, this becomes a critical span for latency analysis

**Why these specific spans?** Each one represents a **decision point** in the code where the outcome affects the trace story. A span around "calculate total" is useful because if it's slow, the fix is different (optimize pricing logic) than if "persist order" is slow (optimize database).

---

## Q: How does all this feed into a RAG pipeline?

The ultimate goal is building a RAG pipeline on traces. Here's how each instrumentation decision connects to that:

1. **Spans are your documents.** Each span becomes a document in your vector store: service name, operation, duration, status, parent span, attributes.

2. **TraceId is your join key.** When a user asks "why was order #1234 slow?", the RAG retrieves all spans with that traceId and presents the waterfall.

3. **Span attributes are your metadata.** `http.method`, `http.url`, `db.statement`, `error.message` — these become filterable fields. "Show me all payment failures in the last hour" is a metadata filter, not a semantic search.

4. **Log correlation is your context.** The traceId links spans to log lines. A span says "this was slow"; the correlated log says "connection pool exhausted, waited 4s for connection." The RAG pipeline needs both.

5. **Structured JSON logs are queryable.** Plain text logs require regex parsing. JSON logs with typed fields (traceId, level, service, duration) are directly indexable. For RAG, this means faster retrieval and more accurate context.

The instrumentation decisions made today determine what questions you can answer tomorrow. If you don't trace database queries, no amount of RAG sophistication will tell you about the N+1 problem. If you don't add custom spans, the RAG can say "payment-service was slow" but not "the fraud check within payment-service was slow."

**Instrument for the questions you want to answer.**
