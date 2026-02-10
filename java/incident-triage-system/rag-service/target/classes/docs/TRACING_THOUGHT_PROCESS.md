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
| Telemetry ingestion (Week 3) | ❌ Future | Pull traces from Jaeger API → embed into ChromaDB |

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

**Week 2 (now):** We built a RAG pipeline that answers questions about the system → using project documentation.

**Week 3 (next):** We'll connect the two — pull traces from Jaeger's API, chunk them, embed them into ChromaDB, and answer questions like:
- "Why was the last order slow?" → retrieves actual trace data showing the slow span
- "Which service has the most errors this hour?" → retrieves error spans and summarizes
- "What pattern do payment failures follow?" → retrieves error traces and finds commonalities

**This is the full vision:** observability data (traces, logs, metrics) becomes queryable through natural language. Instead of writing PromQL queries or Jaeger search filters, you ask a question and the RAG pipeline finds the relevant telemetry and explains it.

**Instrument for the questions you want to answer.**
