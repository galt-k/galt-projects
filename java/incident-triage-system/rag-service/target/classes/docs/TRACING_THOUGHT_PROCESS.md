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

## Q: What's the current instrumentation status?

| Item | Status | Details |
|------|--------|---------|
| HTTP auto-instrumentation | ✅ Done | Micrometer + RestClient.Builder handles this |
| Custom business spans | ✅ Done | `@Observed` on key methods + `ObservedAspect` bean |
| OTLP export to Jaeger | ✅ Done | OTLP HTTP endpoint → Jaeger :4318 |
| Trace correlation in logs | ✅ Done | traceId/spanId in log pattern |
| RAG pipeline (Week 2) | ✅ Done | LangChain4j + Ollama + ChromaDB |
| Database query spans | ❌ Future | Need JDBC/JPA instrumentation dependency |
| Structured JSON logging | ❌ Future | Need logstash-logback-encoder + logback-spring.xml |
| Telemetry ingestion (Week 3) | ✅ Done | JaegerClient → TelemetryIngestionService → ChromaDB |

---

## Q: What custom spans did I add and why?

We added `@Observed` annotations on key business methods. Here's what's instrumented and the rationale:

**product-service:**
- `@Observed("get-all-products")` on `ProductService.getAllProducts()` — tracks catalog listing performance
- `@Observed("get-product-by-id")` on `ProductService.getProductById()` — tracks individual product lookups; critical because order-service calls this per item

**order-service:**
- `@Observed("create-order")` on `OrderService.createOrder()` — wraps the entire order orchestration (validation → save → payment)
- `@Observed("validate-order-items")` on `OrderService.validateAndBuildItems()` — wraps the product validation loop. This is a separate span because isolating validation time from payment time tells you different things about latency

**payment-service:**
- `@Observed("process-payment")` on `PaymentService.processPayment()` — wraps payment processing including the 150ms simulated delay

**Why `@Observed` over manual Span API?**
- Declarative: one annotation vs 10+ lines of try/finally span management
- Automatic error recording: exceptions automatically set the span's error status
- Micrometer integration: `@Observed` creates both a span AND a timer metric — two-for-one instrumentation
- Requires: `spring-boot-starter-aop` dependency + `ObservedAspect` bean registered in each service's `ObservationConfig`

**Why these specific spans?** Each one represents a **decision point** in the code where the outcome affects the trace story. A span around "validate-order-items" is useful because if it's slow, the fix is different (product-service is slow, or too many items) than if "process-payment" is slow (payment gateway latency).

**Spans we could still add:**
- `check-stock-availability` — when stock checks become complex (reserved stock, warehouse allocation)
- `calculate-order-total` — usually fast, but custom pricing logic can be surprisingly slow
- `fraud-check` — when fraud detection is added to payment-service

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

---

## Q: How does the RAG pipeline (Week 2) actually work?

The `rag-service` is a fourth microservice that uses Retrieval-Augmented Generation to answer questions about the system. It doesn't participate in the order flow — it's an observability/knowledge tool.

**The pipeline has two phases:**

**Phase 1: Ingestion (`POST /ingest`)**
1. Load `.md` files from `classpath:docs/` (currently DESIGN.md and this file)
2. Split each document into chunks of 1000 characters with 200-character overlap using `DocumentSplitters.recursive()`
3. Generate vector embeddings for each chunk via Ollama's `nomic-embed-text` model
4. Store embeddings + text in ChromaDB (vector database)

**Phase 2: Query (`POST /ask`)**
1. User submits a question: `{"question": "How does trace propagation work?"}`
2. The question is embedded using the same `nomic-embed-text` model
3. ChromaDB performs similarity search — returns the top 5 most relevant chunks (min score 0.5)
4. Chunks are assembled into a prompt with the original question
5. The prompt is sent to Ollama's `llama3.2` chat model
6. The LLM generates an answer grounded in the retrieved context

**Why this architecture?** LLMs hallucinate. By retrieving actual documentation and feeding it as context, the answers are grounded in facts. The model can only use what we give it — that's the "retrieval-augmented" part.

---

## Q: Why LangChain4j? Why not Spring AI?

Both are viable, but LangChain4j was chosen for several reasons:

1. **More mature RAG abstractions** — LangChain4j has built-in `EmbeddingStoreIngestor`, `DocumentSplitters`, and `ContentRetriever` that compose cleanly. Spring AI's RAG support is newer and less battle-tested.

2. **BOM-managed dependencies** — The `langchain4j-bom` ensures all modules (core, ollama, chroma, tika) have compatible versions. No version conflicts.

3. **Ollama Spring Boot Starter** — `langchain4j-ollama-spring-boot-starter` auto-configures both chat and embedding models from `application.yml` properties. Zero boilerplate.

4. **Direct ChatModel interface** — `ChatModel.chat(String)` takes a prompt string and returns a response string. Simple enough for learning, but the same interface supports streaming, tool use, and structured output when you need it.

**Trade-off:** Spring AI is the "official" Spring ecosystem choice and has deeper Spring Boot integration. If this project were production Spring-first, Spring AI might be the better bet. For learning RAG concepts, LangChain4j's explicit pipeline is more educational.

---

## Q: Why Ollama instead of OpenAI/Claude API?

1. **Free** — No API key, no billing, no rate limits. Critical for a learning project where you'll run many iterations.
2. **Local** — All data stays on your machine. No privacy concerns with sending trace data to a cloud API.
3. **Fast iteration** — No network latency to an API endpoint. Model responses are limited by your machine's speed, not API quotas.
4. **Swappable** — The `ChatModel` interface is the same regardless of provider. Switching to OpenAI later is a config change, not a code change.

**Models chosen:**
- `llama3.2` (~2GB) for chat — lightweight but capable enough for Q&A over documentation
- `nomic-embed-text` (~274MB) for embeddings — small, fast, good quality for text similarity

---

## Q: Why ChromaDB instead of Weaviate, Pinecone, or pgvector?

**Simplicity.** ChromaDB is:
- One Docker container: `docker run -d -p 8000:8000 chromadb/chroma:latest`
- No authentication, no schema setup, no cloud account
- Collections are created automatically on first insert
- Good enough for thousands of document chunks (our scale)

**Trade-offs we accepted:**
- No persistence guarantees (fine for dev — re-ingest if data is lost)
- No built-in auth (fine for local dev)
- Limited query features compared to Weaviate (don't need them yet)

For Week 3 (telemetry ingestion), ChromaDB's simplicity becomes even more valuable — we'll be experimenting with different chunking strategies for traces, and fast iteration matters more than durability.

---

## Q: Why recursive document splitting at 1000/200?

**Chunk size: 1000 characters**
- Too small (e.g., 200 chars): chunks lose context, answers are fragmented
- Too large (e.g., 5000 chars): chunks are diluted with irrelevant info, embedding quality drops
- 1000 chars: roughly a paragraph — usually captures one complete thought

**Overlap: 200 characters**
- Without overlap: sentences at chunk boundaries get split, losing meaning
- 200 chars: ensures the last ~2 sentences of chunk N appear in the start of chunk N+1
- This prevents "lost context at boundaries" — a common RAG failure mode

**Recursive splitting** is smarter than naive character splitting — it tries to split on paragraph breaks, then sentence breaks, then word breaks. This preserves semantic units better than splitting mid-sentence.

**For Week 3:** Trace data will need a different chunking strategy. A single trace might be chunked per span, per service, or per error pattern. The 1000/200 strategy works for prose documentation but may not be optimal for structured telemetry data.

---

## Q: Why embed our own DESIGN.md and TRACING_THOUGHT_PROCESS.md?

This is the most interesting design choice: **the system can explain itself.**

The RAG service ingests the very documents that describe how the system works. So you can ask:
- "How does trace propagation work between services?" → answers from DESIGN.md
- "Why do we use RestClient instead of RestTemplate?" → answers from DESIGN.md
- "How would tracing help debug a slow order?" → answers from THIS document

**This is deliberately self-referential for learning purposes.** In a production system, you'd embed:
- Runbooks (how to respond to alerts)
- Architecture docs (how services interact)
- Incident postmortems (what went wrong before)
- And eventually — traces and spans themselves (Week 3)

The pattern is the same regardless of what you embed. Start with docs → add traces → add logs → add metrics. Each layer makes the RAG more capable.

---

## Q: How does the RAG pipeline connect to the tracing story?

**Week 1:** We built microservices with distributed tracing → traces exported to Jaeger.

**Week 2:** We built a RAG pipeline that answers questions about the system → using project documentation.

**Week 3 (done):** We connected the two. `JaegerClient` pulls traces from Jaeger's REST API, `TelemetryIngestionService` converts them to human-readable narratives, chunks them, and embeds them into ChromaDB. Now you can ask:
- "Why was the last order slow?" → retrieves actual trace data showing the slow span
- "Which service has the most errors this hour?" → retrieves error spans and summarizes
- "What pattern do payment failures follow?" → retrieves error traces and finds commonalities

**This is the full vision realized:** observability data (traces) is now queryable through natural language. Instead of writing Jaeger search filters, you ask a question and the RAG pipeline finds the relevant telemetry and explains it.

**Instrument for the questions you want to answer.**

---

## Q: How does telemetry ingestion (Week 3) work?

Three new components make this possible:

**1. `JaegerClient`** — HTTP client that talks to Jaeger's REST API:
- `GET /api/services` → discovers all traced services (product-service, order-service, payment-service)
- `GET /api/traces?service=X&lookback=1h&limit=20` → fetches recent traces per service
- Skips Jaeger's own internal services (jaeger-query, jaeger-all-in-one)
- Uses Spring's `RestClient` (not the auto-configured Builder — this client doesn't need tracing on itself)

**2. `TelemetryIngestionService`** — The core conversion layer:
- Receives raw Jaeger trace JSON (traceID, spans array, processes map)
- Parses each span: operation name, service name, duration, parent-child relationships, tags, error status
- Converts the full trace into a **human-readable narrative** (not raw JSON)
- Attaches rich metadata: traceId, rootService, rootOperation, durationMs, spanCount, hasErrors
- Adds performance annotations for slow traces (>500ms) identifying the bottleneck
- Adds error summaries for traces with failures

**3. `POST /ingest/traces` endpoint** — Takes optional `lookback` and `limit` parameters

**Why human-readable narratives instead of raw JSON?**

LLMs understand prose far better than nested JSON. Compare:

Raw JSON span: `{"operationName":"GET","duration":15234,"tags":[{"key":"http.status_code","value":"200"}]}`

Human-readable: `[product-service] GET /products/1  (15ms)\n  http.status_code: 200`

The narrative format means the LLM can directly reason about what happened, identify patterns, and explain problems in natural language. Raw JSON would require the LLM to parse structure before reasoning.

---

## Q: Why chunk per-trace and not per-span?

This was the most important chunking decision for telemetry.

**Per-span chunking (rejected):**
- "GET /products/1 took 15ms" — so what? Is that slow? For what request? What happened before and after?
- A span without its trace context is like a sentence without its paragraph — technically valid but meaningless

**Per-trace chunking (chosen):**
- "Order creation: 400ms total. Validation: 50ms (2 product lookups at 15ms each). Payment: 170ms (150ms simulated delay). All services responded. No errors."
- The trace tells a complete request story — who called whom, how long each step took, where the bottleneck is

**Per-service chunking (considered but not chosen):**
- Would group all spans from one service together
- Loses the cross-service narrative — the whole point of distributed tracing
- Might be useful for "show me all product-service activity" queries, but that's a metadata filter, not a semantic search

**The trade-off:** Per-trace documents can be large (2000+ chars for a trace with 10 spans). We compensate with larger chunk size (1500/300 vs 1000/200 for docs) to keep most traces within 1-2 chunks.

---

## Q: Why are trace chunks larger than documentation chunks?

**Docs: 1000 chars, 200 overlap**
- Prose paragraphs are self-contained at ~1000 chars
- Natural breakpoints exist (paragraphs, headings)
- Smaller chunks = more precise retrieval for conceptual questions

**Traces: 1500 chars, 300 overlap**
- A trace with 8 spans is typically 1500-2500 chars
- The span breakdown is a single logical unit — splitting mid-trace loses context
- Larger chunks keep most traces in 1-2 chunks rather than fragmenting across 3-4
- 300-char overlap ensures span data at chunk boundaries isn't lost

---

## Q: What metadata do trace documents carry?

Each trace document in ChromaDB has these metadata fields:

| Field | Type | Example | Purpose |
|-------|------|---------|---------|
| `source` | String | `"jaeger-trace"` | Distinguishes traces from docs |
| `traceId` | String | `"abc123def456"` | Unique trace identifier |
| `rootService` | String | `"order-service"` | Which service received the initial request |
| `rootOperation` | String | `"POST /orders"` | The entry-point operation |
| `durationMs` | Integer | `385` | Total trace duration in milliseconds |
| `spanCount` | Integer | `11` | Number of spans in the trace |
| `hasErrors` | String | `"true"/"false"` | Whether any span had an error |
| `type` | String | `"telemetry"` | Data type marker for filtering |

This metadata enables future improvements: filter by service, find only error traces, find traces slower than a threshold. Currently used for provenance — the RAG prompt tells the LLM to distinguish documentation context from telemetry context.

---

# Real-World Scenarios: OTel Collector, GenAI, and Production Observability

Everything above focuses on our learning project. This section covers how these patterns scale to **real production systems** — especially the role of the **OpenTelemetry Collector** and how **GenAI/RAG** fits into the observability stack.

---

## Q: What is the OpenTelemetry Collector and why does every production system need one?

The OTel Collector is a **vendor-agnostic telemetry router**. It sits between your applications (producers) and your observability backends (consumers).

```
┌─────────────────────────────────────────────────────────────────────┐
│                    PRODUCTION ARCHITECTURE                          │
│                                                                     │
│  Team A services ──┐                                                │
│  Team B services ──┤                                                │
│  Team C services ──┤──OTLP──→ ┌───────────────────┐                │
│  Team D services ──┤          │  OTel Collector    │                │
│  Infra agents    ──┤          │                    │                │
│  K8s metrics     ──┘          │  Receivers:        │                │
│                               │   - OTLP (gRPC)   │                │
│                               │   - OTLP (HTTP)   │                │
│                               │   - Prometheus     │                │
│                               │   - Kafka          │                │
│                               │                    │                │
│                               │  Processors:       │                │
│                               │   - Batch          │──→ Jaeger/Tempo│
│                               │   - Filter         │──→ Prometheus  │
│                               │   - Sampling       │──→ Loki        │
│                               │   - Attributes     │──→ S3/GCS      │
│                               │   - K8s metadata   │──→ PagerDuty   │
│                               │   - Tail sampling  │──→ RAG/AI svc  │
│                               │                    │──→ Kafka topic  │
│                               └───────────────────┘                │
└─────────────────────────────────────────────────────────────────────┘
```

**Without a Collector:**
- Every service needs to know where Jaeger, Prometheus, Loki, etc. live
- Changing backends requires redeploying every service
- No central place to filter, sample, or enrich
- Cost spirals because everything is exported everywhere

**With a Collector:**
- Services send OTLP to one endpoint — done
- Routing, sampling, enrichment all happen centrally
- Backend migrations are config changes, not code changes
- The Platform/SRE team owns the Collector; app teams don't care

---

## Q: How would the OTel Collector work with our system?

**Current architecture (direct export, polling):**
```
product-service ──OTLP──→ Jaeger ←──poll── rag-service
order-service   ──OTLP──→ Jaeger
payment-service ──OTLP──→ Jaeger
```

**With Collector (fan-out, real-time):**
```
product-service ──OTLP──→ ┌───────────────┐ ──OTLP──→ Jaeger :4318
order-service   ──OTLP──→ │ OTel Collector│ ──HTTP───→ rag-service :8084/ingest/trace
payment-service ──OTLP──→ └───────────────┘
```

The Collector config (YAML) would look like:
```yaml
receivers:
  otlp:
    protocols:
      http:
        endpoint: 0.0.0.0:4318

exporters:
  otlphttp/jaeger:
    endpoint: http://jaeger:4318

  otlphttp/rag:
    endpoint: http://rag-service:8084/ingest/trace

processors:
  batch:
    timeout: 5s
    send_batch_size: 100

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [batch]
      exporters: [otlphttp/jaeger, otlphttp/rag]
```

**What changes:**
- Services point OTLP at Collector instead of Jaeger directly
- Collector exports to both Jaeger AND rag-service
- rag-service gets traces in real-time — no polling needed
- Adding a new consumer (e.g., anomaly detection) = add one exporter line

---

## Q: What are the production scenarios for the OTel Collector?

### Scenario 1: Multi-team platform (50+ services)

```
Team Checkout:  checkout-svc, cart-svc, pricing-svc
Team Inventory: warehouse-svc, stock-svc, shipping-svc
Team Payments:  payment-svc, billing-svc, refund-svc
Team Auth:      auth-svc, user-svc, session-svc
Team Search:    search-svc, recommend-svc, catalog-svc
                     │
                     ▼
              OTel Collector (owned by Platform team)
                     │
          ┌──────────┼──────────┬──────────────┐
          ▼          ▼          ▼              ▼
       Jaeger    Prometheus    Loki       PagerDuty
     (traces)    (metrics)   (logs)       (alerts)
```

**Why this matters:**
- Teams instrument their services once (`micrometer-tracing-bridge-otel` in Spring Boot)
- Platform team decides where data goes — teams don't even know
- If Platform team migrates from Jaeger to Grafana Tempo, zero app changes
- Each team sees only their own traces in the UI (multi-tenancy via Collector attributes)

### Scenario 2: Cost control via intelligent sampling

At scale, storing every trace is prohibitively expensive. A company doing 100K requests/second generates billions of spans/day.

```
All traces ──→ OTel Collector
                  │
                  ├── Tail Sampling Processor:
                  │     - 100% of ERROR traces       → kept (all errors matter)
                  │     - 100% of traces > 2 seconds  → kept (all slow requests matter)
                  │     - 5% of successful traces     → kept (sample of normal traffic)
                  │     - 100% of traces with custom  → kept (business-critical flows)
                  │       attribute "payment.amount > 10000"
                  │
                  └── Result: 90% cost reduction, 0% loss of important data
```

**Tail sampling** is only possible with a Collector. It waits for the entire trace to complete, then decides whether to keep or drop it based on the full picture. Head sampling (at the service level) can't do this because the service doesn't know if the downstream call will fail.

### Scenario 3: Data enrichment with Kubernetes metadata

Services running in K8s don't know their own pod name, node, namespace, or deployment version. The Collector adds this automatically:

```
Span from service:
  service.name: order-service
  http.method: POST
  http.url: /orders

After Collector's k8sattributes processor:
  service.name: order-service
  http.method: POST
  http.url: /orders
  k8s.pod.name: order-service-7b4d5f6-xk2m9       ← added
  k8s.namespace: production                          ← added
  k8s.deployment.name: order-service                 ← added
  k8s.node.name: ip-10-0-42-17.ec2.internal         ← added
  cloud.region: us-east-1                            ← added
  deployment.version: v2.3.1                         ← added
```

**Why this matters for debugging:** When order-service is slow, you can now see if it's one specific pod (memory leak), one node (noisy neighbor), or all pods (code issue). Without K8s enrichment, you just see "order-service is slow."

### Scenario 4: Zero-downtime backend migration

Company is migrating from Jaeger to Grafana Tempo:

```
Week 1:  Collector ──→ Jaeger (100%)
                   ──→ Tempo (100%, shadow mode — data flows but nobody looks at it)

Week 2:  Teams validate Tempo has matching traces
         Compare: same traceId in Jaeger == same traceId in Tempo ✓

Week 3:  Teams switch dashboards to Tempo
         Collector ──→ Jaeger (still receiving, but nobody uses it)
                   ──→ Tempo (primary)

Week 4:  Collector ──→ Tempo (100%)
         Jaeger removed.
```

**Zero code changes in any service.** The migration is purely a Collector config change. Services never knew about the switch.

### Scenario 5: Multi-region with local Collectors

```
US-East Region:                        EU-West Region:
┌─────────────────────┐                ┌─────────────────────┐
│ services ──→ Local  │                │ services ──→ Local  │
│             Collector──→ Central     │             Collector──→ Central
└─────────────────────┘   Collector    └─────────────────────┘   Collector
                              │
                    ┌─────────┼─────────┐
                    ▼         ▼         ▼
                 Jaeger   Prometheus   S3 Archive
```

Local Collectors batch and compress before sending cross-region. This reduces cross-region bandwidth costs and adds resilience — if the central Collector is down, local Collectors buffer data.

### Scenario 6: Compliance and data redaction

In regulated industries (healthcare, finance), traces may contain PII:

```
Span: POST /api/users
  user.email: john@example.com     ← PII!
  user.ssn: 123-45-6789           ← PII!
  http.url: /api/users?name=John  ← PII in URL!

After Collector's attributes processor (redaction):
  user.email: ***@***.com
  user.ssn: ***-**-****
  http.url: /api/users?name=***
```

The Collector can **redact sensitive fields** before data reaches any backend. Services don't need to know about compliance rules — the Collector enforces them centrally.

---

## Q: How does the OTel Collector enable GenAI/RAG at scale?

This is where our project's architecture connects to the real world. The Collector becomes the **data pipeline** feeding AI systems.

### Pattern 1: Real-time trace embedding for RAG

```
services ──→ Collector ──→ Jaeger (human visualization)
                       ──→ RAG Service (AI embedding)
                               │
                               ├── Convert trace to narrative
                               ├── Embed via LLM
                               └── Store in vector DB
                                      │
                                      ▼
                               "Why was checkout slow?"
                               → Retrieves actual traces
                               → LLM explains root cause
```

**Production scale consideration:** At 100K traces/day, you can't embed every trace. Use the Collector's sampling processor to send only **interesting** traces to the RAG service:
- Error traces (always)
- Slow traces (p99+)
- Business-critical paths (checkout, payment)
- Sampled normal traffic (1-5%)

### Pattern 2: Anomaly detection pipeline

```
services ──→ Collector ──→ Jaeger
                       ──→ Anomaly Detection Service
                               │
                               ├── Running average latency per operation
                               ├── When current trace >> avg: flag as anomaly
                               ├── Embed anomaly trace + context into RAG
                               └── Generate incident summary via LLM
                                      │
                                      ▼
                               Slack: "⚠️ payment-service latency spiked 10x
                               in the last 5 minutes. 3 traces show timeout
                               connecting to payment gateway. Similar pattern
                               occurred on Jan 15 (resolved by gateway team).
                               Suggested action: check gateway status page."
```

The LLM doesn't just detect the anomaly — it **correlates with past incidents** (stored in the vector DB from previous embeddings) and suggests actions. This is the "AI-powered on-call" vision.

### Pattern 3: Automated incident postmortem

```
During incident:
  Collector ──→ RAG Service (all error traces auto-embedded)

After incident resolved:
  Engineer: "Generate a postmortem for the payment outage between 2-3 PM"

  RAG pipeline:
  1. Retrieve all error traces from 2-3 PM (metadata filter: hasErrors=true, time range)
  2. Retrieve architecture docs (how payment-service works)
  3. Retrieve past incidents with similar patterns
  4. LLM generates structured postmortem:
     - Timeline of events
     - Root cause analysis
     - Impact assessment
     - Similar past incidents
     - Recommended preventive actions
```

This turns hours of manual postmortem writing into a 30-second AI-generated draft that the engineer reviews and refines.

### Pattern 4: Natural language SLA monitoring

```
Product Manager: "Are we meeting our 500ms p99 SLA for checkout?"

RAG pipeline:
1. Retrieve recent checkout traces from vector DB
2. Calculate: 95th percentile = 420ms, 99th percentile = 890ms
3. Retrieve SLA documentation
4. LLM: "No. Your checkout p99 is 890ms, exceeding the 500ms SLA.
   The bottleneck is inventory-service (contributing 400ms average).
   Traces show inventory DB queries taking 350ms when stock is low
   (full table scan on inventory table). Recommendation: add index
   on (product_id, warehouse_id) in inventory DB."
```

Non-technical stakeholders can query system health in plain English.

### Pattern 5: Predictive failure detection

```
Collector ──→ RAG Service (continuous embedding)
                │
                └── LLM analyzes trace patterns over time:
                    - "payment-service latency increasing 5% per day for last week"
                    - "connection pool utilization at 85% and growing"
                    - Past incident data shows: "Last time pool hit 95%, cascading failure"

                    → Proactive alert: "payment-service connection pool will likely
                      exhaust in ~3 days at current growth rate. Similar pattern
                      preceded the Feb 3 outage. Consider increasing pool size
                      or investigating connection leak."
```

The AI moves from **reactive** (explain what happened) to **proactive** (predict what will happen).

---

## Q: What data should we feed into the GenAI/RAG pipeline beyond traces?

Traces are just the start. A production RAG observability system ingests multiple data types:

| Data Source | What It Provides | Example Questions It Answers |
|-------------|-----------------|------------------------------|
| **Traces** (Jaeger/Tempo) | Request flow, latency, errors, service dependencies | "Why was this order slow?" |
| **Metrics** (Prometheus) | CPU, memory, request rates, error rates, saturation | "Is order-service overloaded?" |
| **Logs** (Loki/ELK) | Detailed error messages, stack traces, business events | "What exception caused this failure?" |
| **K8s events** | Pod restarts, OOM kills, node pressure, scaling events | "Was there a deployment during the outage?" |
| **Deployment history** (ArgoCD/Flux) | What changed, when, who deployed | "Did a recent deploy cause this regression?" |
| **Incident history** (PagerDuty/Opsgenie) | Past incidents, resolutions, runbooks | "Has this happened before? How was it fixed?" |
| **Architecture docs** (Confluence/Notion) | System design, dependencies, SLAs | "How is payment-service supposed to work?" |
| **Runbooks** (wiki/docs) | Step-by-step troubleshooting guides | "How do I restart the payment gateway connection?" |
| **Git commits** (GitHub/GitLab) | Code changes, PR descriptions, blame | "What code change caused this latency increase?" |
| **Alerts** (Grafana/PagerDuty) | What's firing, thresholds, escalation state | "What alerts are active right now?" |

**The richer the context, the better the AI's answers.** A trace alone says "payment was slow." A trace + metrics + logs + deploy history says "payment was slow because the v2.3.1 deploy introduced an N+1 query in the payment validation path, causing DB CPU to spike to 95%."

---

## Q: How would we architect the Collector pipeline for GenAI in production?

```
┌──────────────────────────────────────────────────────────────────────────┐
│                 PRODUCTION GENAI OBSERVABILITY PIPELINE                  │
│                                                                          │
│  ┌──────────┐     ┌───────────────────┐     ┌─────────────────────┐      │
│  │ Services │     │   OTel Collector  │     │   STREAMING LAYER   │      │
│  │ (OTLP)   │──→  │                   │──→  │                     │      │
│  └──────────┘     │  Processors:      │     │   Kafka Topics:     │      │
│                   │  - Batch          │     │   - traces.all      │      │
│  ┌──────────┐     │  - K8s attributes │     │   - traces.errors   │      │
│  │ K8s      │──→  │  - Tail sampling  │     │   - traces.slow     │      │
│  │ Metrics  │     │  - PII redaction  │     │   - metrics.all     │      │
│  └──────────┘     │  - Routing        │     │   - logs.errors     │      │
│                   └───────────────────┘     └──────┬──────────────┘      │
│  ┌──────────┐                                      │                     │
│  │ Logs     │──→  (Collector)                      │                     │
│  │ (fluentd)│                                      │                     │
│  └──────────┘                                      │                     │
│                                                    │                     │
│                        ┌───────────────────────────┼──────────┐          │
│                        │                           │          │          │
│                        ▼                           ▼          ▼          │
│              ┌────────────────┐          ┌──────────────┐ ┌─────────┐    │
│              │  OBSERVABILITY │          │  AI PIPELINE │ │ALERTING │    │
│              │  BACKENDS      │          │              │ │         │    │
│              │                │          │  Embedding   │ │PagerDuty│    │ 
│              │  Jaeger/Tempo  │          │  Service     │ │Slack    │    │
│              │  Prometheus    │          │     │        │ │OpsGenie │    │
│              │  Loki          │          │     ▼        │ └─────────┘    │
│              │  Grafana       │          │  Vector DB   │                │
│              └────────────────┘          │  (Weaviate/  │                │
│                                          │   Pinecone)  │                │
│                                          │     │        │                │
│                                          │     ▼        │                │
│                                          │  RAG Engine  │                │
│                                          │  + LLM       │                │
│                                          │     │        │                │
│                                          │     ▼        │                │
│                                          │  AI Agent    │                │
│                                          │  (chat,      │                │
│                                          │   postmortem,│                │
│                                          │   anomaly    │                │
│                                          │   detection) │                │
│                                          └──────────────┘                │
└──────────────────────────────────────────────────────────────────────────┘
```

**Key architectural decisions:**

1. **Kafka between Collector and AI** — Decouples ingestion rate from embedding rate. If the LLM is slow, Kafka buffers. If the LLM is down, no data loss.

2. **Separate Kafka topics** by signal type and priority — `traces.errors` gets processed immediately; `traces.all` can be sampled.

3. **Vector DB choice matters at scale** — ChromaDB is fine for learning. Production needs Weaviate, Pinecone, or pgvector for durability, authentication, multi-tenancy, and billions of vectors.

4. **LLM choice matters at scale** — Ollama is free for local. Production needs to balance cost vs quality:
   - Embeddings: `text-embedding-3-small` (OpenAI) or self-hosted `nomic-embed-text`
   - Chat: GPT-4o for complex reasoning, GPT-4o-mini for simple queries, or self-hosted Llama for data privacy

---

## Q: What are the Collector deployment patterns?

### Pattern 1: Sidecar (per-pod)

```
┌──────────────────────────┐
│  Kubernetes Pod          │
│  ┌──────────┐ ┌────────┐ │
│  │ App      │→│ OTel   │ │──→ Central Collector
│  │ Container│ │Sidecar │ │
│  └──────────┘ └────────┘ │
└──────────────────────────┘
```

- Every pod gets its own Collector sidecar
- Collects traces, metrics, and logs from the app container
- Forwards to a central Collector
- **Pros:** Isolation, per-pod buffering, app doesn't need to know about backends
- **Cons:** Resource overhead per pod (100-200MB per sidecar)

### Pattern 2: DaemonSet (per-node)

```
┌─────────────────────────────────────┐
│  Kubernetes Node                    │
│  ┌──────┐ ┌──────┐ ┌──────┐         │
│  │Pod A │ │Pod B │ │Pod C │         │
│  └──┬───┘ └──┬───┘ └──┬───┘         │
│     └────────┼────────┘             │
│              ▼                      │
│     ┌────────────────┐              │
│     │ OTel Collector │──→ Central   │
│     │ (DaemonSet)    │   Collector  │
│     └────────────────┘              │
└─────────────────────────────────────┘
```

- One Collector per K8s node
- All pods on the node send to the same Collector
- **Pros:** Less resource overhead than sidecar, can collect node-level metrics
- **Cons:** Noisy neighbor if one pod floods the Collector

### Pattern 3: Gateway (centralized)

```
Pod A ──OTLP──→ ┌───────────────────┐
Pod B ──OTLP──→ │  OTel Collector   │──→ Backends
Pod C ──OTLP──→ │  (Deployment,     │
Pod D ──OTLP──→ │   2-3 replicas,   │
Pod E ──OTLP──→ │   load balanced)  │
                └───────────────────┘
```

- Single centralized Collector behind a load balancer
- All services send directly to it
- **Pros:** Simplest setup, easiest to manage, central processing
- **Cons:** Single point of failure (mitigated with replicas), can be bottleneck at very high scale

### Production recommendation:

**DaemonSet (nodes) → Gateway (central)** is the most common production pattern:

```
Pods ──→ Node DaemonSet Collector ──→ Gateway Collector ──→ Backends
         (collects + buffers)        (processes + routes)    (stores)
```

---

## Q: How do you handle the Collector in Docker Compose (for local dev)?

For our project, adding a Collector to Docker Compose would look like:

```yaml
otel-collector:
  image: otel/opentelemetry-collector-contrib:latest
  ports:
    - "4318:4318"     # OTLP HTTP receiver
    - "4317:4317"     # OTLP gRPC receiver
  volumes:
    - ./otel-collector-config.yaml:/etc/otelcol-contrib/config.yaml
  depends_on:
    - jaeger
```

Services would change their OTLP endpoint from `http://jaeger:4318` to `http://otel-collector:4318`. One config change per service.

The Collector config file defines the full pipeline — receivers, processors, exporters — and can be changed without restarting any service.

---

## Q: What are the real pitfalls and lessons learned with OTel Collector in production?

### Pitfall 1: Collector as bottleneck

If 200 services send traces to one Collector and it can't keep up:
- Traces get buffered in memory → Collector OOM kills → data loss
- **Fix:** Multiple Collector replicas behind a load balancer, or DaemonSet pattern

### Pitfall 2: Tail sampling requires memory

Tail sampling holds traces in memory until they're "complete." If a trace has spans arriving over 30 seconds:
- Collector needs to buffer 30 seconds of all traces in memory
- At 10K traces/second, that's 300K traces in memory
- **Fix:** Set `decision_wait` to a reasonable timeout (5-10s), accept that very long traces may be sampled incorrectly

### Pitfall 3: Configuration complexity

Collector config can get extremely complex with 10+ exporters, 5 processors, multiple pipelines:
```yaml
service:
  pipelines:
    traces/important:
      receivers: [otlp]
      processors: [tail_sampling, k8sattributes, batch]
      exporters: [otlphttp/tempo, otlphttp/rag, debug]
    traces/all:
      receivers: [otlp]
      processors: [head_sampling/10pct, batch]
      exporters: [otlphttp/tempo]
    metrics:
      receivers: [otlp, prometheus]
      processors: [batch, filter/dropinternal]
      exporters: [prometheusremotewrite]
    logs:
      receivers: [otlp, filelog]
      processors: [batch, attributes/addregion]
      exporters: [loki]
```
- **Fix:** Version control the config, use CI/CD to deploy, test config changes in staging first

### Pitfall 4: Cardinality explosion

Services add high-cardinality attributes (user IDs, session tokens, request bodies):
- These inflate metric labels → Prometheus cardinality explosion → OOM
- These inflate trace storage → 10x cost increase
- **Fix:** Use the Collector's `attributes` processor to drop or redact high-cardinality fields before they reach backends

### Pitfall 5: Not monitoring the Collector itself

The Collector is critical infrastructure. If it's down, you lose all observability:
- **Fix:** The Collector exposes its own metrics (queue size, dropped spans, exporter errors). Monitor these with a separate, simple monitoring path that doesn't go through the Collector.

---

## Q: How would an AI-powered on-call assistant work end-to-end?

This is the ultimate vision — combining everything in this document:

```
1. Alert fires:
   PagerDuty → "payment-service error rate > 5% for 5 minutes"

2. AI Agent activates:
   - Retrieves error traces from last 10 min (via RAG/vector DB)
   - Retrieves payment-service architecture docs (via RAG/vector DB)
   - Retrieves past incidents with similar pattern (via RAG/vector DB)
   - Checks current K8s pod status (via MCP server)
   - Checks recent deployments (via MCP server → ArgoCD)
   - Checks Prometheus metrics (via MCP server)

3. AI generates initial assessment:
   "payment-service error rate spiked at 2:14 AM.

   Traces show: ConnectionRefusedException to payment-gateway.stripe.com:443
   started at 2:13:47 AM. All 47 error traces have the same root cause.

   K8s status: 3/3 pods running, no restarts, memory/CPU normal.
   No deployments in last 24 hours.

   This matches the pattern from the Jan 15 incident (INC-2847), which was
   caused by Stripe's us-east-1 outage. Resolution was: wait for Stripe
   to recover, enable circuit breaker to fail fast.

   Recommended actions:
   1. Check Stripe status page: https://status.stripe.com
   2. Enable circuit breaker: set PAYMENT_CIRCUIT_BREAKER_ENABLED=true
   3. If Stripe is down, set up payment retry queue

   Confidence: HIGH (47/47 traces show identical external dependency failure)"

4. On-call engineer reviews, confirms, takes action in minutes instead of hours.
```

**What makes this possible:**
- OTel Collector feeding traces to the RAG pipeline in real-time
- Vector DB with embedded traces, docs, runbooks, and past incidents
- MCP servers for live system access (K8s, Prometheus, deploy tools)
- LLM that can reason across all these data sources

**What we've built so far:**
- ✅ Microservices with distributed tracing (Week 1)
- ✅ RAG pipeline with documentation (Week 2)
- ✅ Telemetry ingestion from Jaeger (Week 3)
- ✅ RAG precision + Resilience4j circuit breaker (Week 4)
- 🔲 OTel Collector for real-time fan-out (future)
- 🔲 MCP servers for live system access (future)
- 🔲 Multi-source ingestion (logs, metrics, K8s events) (future)
- 🔲 Anomaly detection and proactive alerting (future)

Each step in this project builds toward that vision. The foundation is solid.

---

## Q: Why does the RAG service need Resilience4j? What fails without it?

The rag-service makes external calls to **three dependencies**: Ollama (LLM + embeddings), ChromaDB (vector store), and Jaeger (trace source). Any of these can fail independently.

**Without Resilience4j — what happens when Ollama goes down:**

```
User: POST /ask {"question": "Why was the last order slow?"}
  ↓
RagQueryService.ask()
  ↓
contentRetriever.retrieve() → Ollama /api/embed → TIMEOUT (120 seconds!)
  ↓
HTTP 500 Internal Server Error

Every subsequent request waits 120 seconds and fails the same way.
100 users = 100 threads blocked for 120 seconds each = thread pool exhaustion.
```

**With Resilience4j — same scenario:**

```
User: POST /ask {"question": "Why was the last order slow?"}
  ↓
RagQueryService.ask()
  ↓
contentRetriever.retrieve() → Ollama /api/embed → try-catch → graceful error message (instant)
  ↓
HTTP 200 {"answer": "Unable to retrieve context... Ollama is unavailable... will auto-recover."}

If the embedding succeeded but Ollama chat is slow:
  ↓
ollamaChatService.call() → @CircuitBreaker → after 5 failures → OPEN
  ↓
Next requests: CallNotPermittedException → instant fallback (raw chunks returned)
  ↓
No thread blocking. No thread pool exhaustion. Users get useful partial answers.
```

---

## Q: Why is the circuit breaker on a separate bean (OllamaChatService)?

This is the most subtle bug in Spring resilience patterns. It's caused by **Spring AOP proxy behavior**.

**The problem — self-call bypass:**

```java
@Service
public class RagQueryService {

    public String ask(String question) {
        // ... retrieve context ...
        return this.callLlm(prompt, context);  // ← SELF-CALL via 'this'
    }

    @CircuitBreaker(name = "ollamaChat", fallbackMethod = "callFallback")
    public String callLlm(String prompt, String context) {
        return chatModel.chat(prompt);  // ← Circuit breaker is SILENTLY IGNORED
    }
}
```

Spring AOP works by creating a **proxy wrapper** around your bean. When another bean calls your method, it goes through the proxy → Resilience4j intercepts it. But when you call a method on `this` within the same class, you bypass the proxy entirely:

```
External caller → Proxy(RagQueryService) → @CircuitBreaker → callLlm()  ✅ Works
         this → callLlm()  ← Proxy NOT involved → @CircuitBreaker ignored  ❌ Silent fail
```

**The fix — separate bean:**

```java
@Service
public class OllamaChatService {  // ← SEPARATE BEAN = separate proxy

    @CircuitBreaker(name = "ollamaChat", fallbackMethod = "callFallback")
    public String call(String prompt, String context) {
        return chatModel.chat(prompt);  // ← Now protected by circuit breaker
    }
}

@Service
public class RagQueryService {
    private final OllamaChatService ollamaChatService;  // ← injected = goes through proxy

    public String ask(String question) {
        // ... retrieve context ...
        return ollamaChatService.call(prompt, context);  // ← PROXY call → CB works ✅
    }
}
```

**This is not a Resilience4j problem — it's a Spring AOP fundamental.** The same issue affects `@Transactional`, `@Cacheable`, `@Async`, and any other annotation-based AOP feature. If you call `this.transactionalMethod()` from within the same class, the transaction boundary is silently ignored.

**Rule of thumb:** If a method needs an AOP annotation to work correctly, it should be on a **different bean** than its caller.

---

## Q: Why is the circuit breaker only on the chat call, not the embedding call?

The `/ask` flow has two Ollama calls:

```
ask(question)
  ├── contentRetriever.retrieve()  → Ollama /api/embed (embed query) + ChromaDB (search)
  └── ollamaChatService.call()     → Ollama /api/chat  (generate answer)
```

**Why the embedding call doesn't get a circuit breaker:**

1. **We don't own the call.** The embedding happens inside LangChain4j's `EmbeddingStoreContentRetriever`. We'd have to wrap or extend the retriever to add a circuit breaker — complex for marginal benefit.

2. **A try-catch is simpler and sufficient.** If embedding fails, we have no context to work with at all. There's no useful fallback — you can't answer a question without any retrieved context. A simple try-catch returns a clear error message.

3. **The circuit breaker's value is in the chat call.** The chat call is the expensive one (10-30 seconds per call). If Ollama is slow but not dead, the circuit breaker prevents thread pool exhaustion by failing fast after detecting a pattern of slow calls. The embedding call is fast (<1 second) so slow-call protection isn't needed.

4. **Both calls fail to the same dependency (Ollama).** If Ollama is completely down, the embedding call fails first (before we even reach the chat call). The circuit breaker on the chat call handles the case where Ollama is degraded — slow responses, intermittent failures — which is actually the harder case to handle.

---

## Q: What are the circuit breaker states and how do transitions work?

```
                    ┌──────────┐
                    │  CLOSED  │ ← Normal operation. All calls go through.
                    │          │   Tracking: count failures in sliding window.
                    └────┬─────┘
                         │
           failure rate >= 50% OR slow call rate >= 80%
                         │
                         ▼
                    ┌──────────┐
                    │   OPEN   │ ← Ollama is considered down. All calls instantly rejected.
                    │          │   CallNotPermittedException → fallback (raw chunks).
                    │          │   No network call made. No thread blocked.
                    └────┬─────┘
                         │
                  after 30 seconds (waitDurationInOpenState)
                         │
                         ▼
                    ┌──────────┐
                    │HALF_OPEN │ ← Testing if Ollama recovered. 3 calls permitted.
                    │          │   If majority succeed → back to CLOSED.
                    │          │   If they fail → back to OPEN for another 30s.
                    └──────────┘
```

**Our specific configuration and why:**

| Parameter | Value | Why |
|-----------|-------|-----|
| `slidingWindowType` | COUNT_BASED | Evaluate the last N calls, not a time window. Simpler to reason about. |
| `slidingWindowSize` | 10 | Look at the last 10 calls. Small enough to react quickly, large enough to avoid false positives from a single timeout. |
| `minimumNumberOfCalls` | 5 | Don't evaluate until at least 5 calls. Prevents tripping on the first few requests at startup. |
| `failureRateThreshold` | 50% | If 5 out of 10 calls fail, Ollama is probably down. |
| `slowCallDurationThreshold` | 25s | A call taking >25s is "slow." Ollama is overloaded or degraded. |
| `slowCallRateThreshold` | 80% | If 8 out of 10 calls are slow, trip the breaker. Don't trip on occasional slowness. |
| `waitDurationInOpenState` | 30s | Wait 30s before trying again. Ollama might restart in ~30s. |
| `permittedNumberOfCallsInHalfOpenState` | 3 | Try 3 calls to confirm recovery. More reliable than a single test call. |
| `timeoutDuration` | 30s | No single call should take more than 30s. Was 120s — lowered to match breaker config. |
