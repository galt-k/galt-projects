# Incident Triage System — Design Document

## 1. Overview

A multi-module Spring Boot microservices project designed to demonstrate distributed tracing across HTTP-based
inter-service communication. Three services collaborate to fulfill an order: validating products, creating orders, and
processing payments. All telemetry is exported via OTLP to Jaeger for trace visualization.

## 2. High-Level Architecture

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                          DEPLOYMENT ENVIRONMENT                              │
│                     (Docker Compose / Kubernetes)                            │
│                                                                              │
│  ┌─────────┐    POST /orders     ┌──────────────────┐                        │
│  │  Client │ ──────────────────▶ │   order-service  │                        │
│  │  (curl) │                     │     :8082        │                        │
│  └────┬────┘                     └────┬──────────┬──┘                        │
│       │                               │          │                           │
│       │        GET /products/{id}     │          │ POST /payments            │
│       │                               ▼          ▼                           │
│       │                  ┌────────────────┐  ┌────────────────┐              │
│       │                  │product-service │  │payment-service │              │
│       │                  │    :8081       │  │    :8083       │              │
│       │                  └───────┬────────┘  └───────┬────────┘              │
│       │                         │                    │                       │
│       │                    ┌────┴────┐          ┌────┴────┐                  │
│       │                    │ H2 (mem)│          │ H2 (mem)│                  │
│       │                    └─────────┘          └─────────┘                  │
│       │                                                                      │
│       │  POST /ask         ┌──────────────────────────────────┐              │
│       └──────────────────▶ │         rag-service              │              │
│          POST /ingest      │           :8084                  │              │
│                            │                                  │              │
│                            │  ┌─────────┐    ┌─────────────┐  │              │
│                            │  │ Ingest  │    │  RAG Query  │  │              │
│                            │  │ Service │    │   Service   │  │              │
│                            │  └────┬────┘    └──┬──────┬───┘  │              │
│                            │       │            │      │      │              │
│                            └───────┼────────────┼──────┼──────┘              │
│                                    │            │      │                     │
│                              embed │    retrieve│  chat│                     │
│                                    ▼            ▼      ▼                     │
│                            ┌────────────┐  ┌──────────────┐                  │
│                            │  ChromaDB  │  │    Ollama    │                  │
│                            │   :8000    │  │   :11434     │                  │
│                            │  (vector)  │  │  (LLM+Embed) │                  │
│                            └────────────┘  └──────────────┘                  │
│                                                                              │
│  ┌──────────────── TELEMETRY PIPELINE ───────────────────┐                   │
│  │                                                       │                   │
│  │  All services ──── OTLP/HTTP ────▶ ┌──────────────┐   │                   │
│  │  (port 4318)                       │   Jaeger     │   │                   │
│  │                                    │   :16686 UI  │   │                   │
│  │  @Observed spans ─┐                │   :4318 OTLP │   │                   │
│  │  HTTP auto-spans ─┤── traceparent  └──────────────┘   │                   │
│  │  Log correlation ─┘   propagation                     │                   │
│  └───────────────────────────────────────────────────────┘                   │
└──────────────────────────────────────────────────────────────────────────────┘
```

## 3. Tech Stack

| Component        | Technology                                 |
|------------------|--------------------------------------------|
| Framework        | Spring Boot 3.5.10                         |
| Language         | Java 21                                    |
| Build Tool       | Maven (multi-module aggregator)            |
| Database         | H2 in-memory (per service)                 |
| ORM              | Spring Data JPA / Hibernate                |
| HTTP Client      | Spring RestClient (Spring 6.1+)            |
| Tracing Bridge   | Micrometer Tracing + OpenTelemetry bridge  |
| Custom Spans     | `@Observed` (Micrometer Observation API)   |
| Trace Export     | OTLP HTTP Exporter → Jaeger                |
| Containerization | Docker + Docker Compose                    |
| Orchestration    | Kubernetes (Minikube for local)            |
| Trace Backend    | Jaeger 2.4 (all-in-one)                    |
| RAG Framework    | LangChain4j 1.11.0                         |
| LLM (local)     | Ollama (llama3.2 chat + nomic-embed-text)  |
| Vector Store     | ChromaDB                                   |
| Doc Parsing      | Apache Tika (via LangChain4j)              |

## 4. Project Structure

```
incident-triage-system/
├── pom.xml                            # Root aggregator POM
├── docker-compose.yml                 # 3 services + Jaeger
├── DESIGN.md                          # This document
├── TRACING_THOUGHT_PROCESS.md         # Q&A on instrumentation decisions
│
├── product-service/
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/
│       ├── java/com/rev/triage/productservice/
│       │   ├── ProductServiceApplication.java
│       │   ├── config/ObservationConfig.java      # ObservedAspect bean
│       │   ├── entity/Product.java
│       │   ├── repository/ProductRepository.java
│       │   ├── service/ProductService.java        # @Observed spans
│       │   ├── controller/ProductController.java
│       │   └── dto/ProductResponse.java
│       └── resources/
│           ├── application.yml
│           └── data.sql
│
├── order-service/
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/
│       ├── java/com/rev/triage/orderservice/
│       │   ├── OrderServiceApplication.java
│       │   ├── config/ObservationConfig.java      # ObservedAspect bean
│       │   ├── config/RestClientConfig.java       # RestClient beans (tracing-aware)
│       │   ├── entity/Order.java
│       │   ├── entity/OrderItem.java
│       │   ├── repository/OrderRepository.java
│       │   ├── service/OrderService.java          # @Observed spans
│       │   ├── controller/OrderController.java
│       │   ├── client/ProductServiceClient.java
│       │   ├── client/PaymentServiceClient.java
│       │   └── dto/
│       │       ├── CreateOrderRequest.java
│       │       ├── OrderResponse.java
│       │       ├── ProductResponse.java
│       │       ├── PaymentRequest.java
│       │       └── PaymentResponse.java
│       └── resources/
│           └── application.yml
│
├── payment-service/
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/
│       ├── java/com/rev/triage/paymentservice/
│       │   ├── PaymentServiceApplication.java
│       │   ├── config/ObservationConfig.java      # ObservedAspect bean
│       │   ├── entity/Payment.java
│       │   ├── repository/PaymentRepository.java
│       │   ├── service/PaymentService.java        # @Observed spans
│       │   ├── controller/PaymentController.java
│       │   └── dto/
│       │       ├── PaymentRequest.java
│       │       └── PaymentResponse.java
│       └── resources/
│           └── application.yml
│
├── rag-service/
│   ├── pom.xml                                    # LangChain4j BOM 1.11.0
│   ├── Dockerfile
│   └── src/main/
│       ├── java/com/rev/triage/ragservice/
│       │   ├── RagServiceApplication.java
│       │   ├── config/RagConfig.java              # ChromaDB + ContentRetriever beans
│       │   ├── service/IngestionService.java      # Doc loading, chunking, embedding
│       │   ├── service/RagQueryService.java       # RAG query (retrieve + LLM chat)
│       │   ├── controller/RagController.java      # /ask, /ingest, /health
│       │   └── dto/
│       │       ├── AskRequest.java
│       │       └── AskResponse.java
│       └── resources/
│           ├── application.yml
│           └── docs/                              # Embedded documents
│               ├── DESIGN.md
│               └── TRACING_THOUGHT_PROCESS.md
│
└── k8s/                               # Kubernetes manifests
    ├── jaeger.yml                     # Jaeger Deployment + Service
    ├── product-service.yml            # Deployment + Service + health probes
    ├── order-service.yml              # Deployment + Service + health probes
    ├── payment-service.yml            # Deployment + Service + health probes
    └── rag-service.yml               # Deployment + Service (1Gi memory)
```

## 5. Service Details

### 5.1 product-service (Port 8081)

**Purpose:** Product catalog — serves product data to other services.

**Endpoints:**

| Method | Path | Description |
| :--- | :--- | :--- |
| GET | `/products` | List all products |
| GET | `/products/{id}` | Get product by ID |

**Data Model — `Product`:**

| Field | Type | Description |
| :--- | :--- | :--- |
| id | Long | Auto-generated primary key |
| name | String | Product name |
| price | BigDecimal | Unit price (never use double) |
| stockQuantity | Integer | Available inventory count |

**Custom Spans:**

| Span Name | contextualName | Purpose |
| :--- | :--- | :--- |
| `product.getAll` | `get-all-products` | Tracks catalog listing performance |
| `product.getById` | `get-product-by-id` | Tracks individual product lookups |

**Seed Data:** 5 products loaded via `data.sql` on startup (Laptop, Mouse, Keyboard, Monitor, Headphones).

### 5.2 order-service (Port 8082)

**Purpose:** Order orchestrator — validates products and triggers payments via HTTP calls to the other two services.

**Endpoints:**

| Method | Path | Description |
| :--- | :--- | :--- |
| POST | `/orders` | Create a new order |

**Data Models:**

**`Order`:**

| Field | Type | Description |
| :--- | :--- | :--- |
| id | Long | Auto-generated primary key |
| status | OrderStatus | CREATED → PAYMENT_PENDING → COMPLETED/FAILED |
| totalAmount | BigDecimal | Sum of (unit_price × quantity) per item |
| paymentId | Long | Foreign reference to payment-service |
| createdAt | LocalDateTime | Order creation timestamp |
| items | List\<OrderItem> | One-to-many cascade |

**`OrderItem`:**

| Field | Type | Description |
| :--- | :--- | :--- |
| id | Long | Auto-generated primary key |
| productId | Long | Reference to product-service product |
| productName | String | Denormalized (snapshot at time of purchase) |
| quantity | Integer | Ordered quantity |
| unitPrice | BigDecimal | Denormalized (snapshot at time of purchase) |
| order | Order | ManyToOne back-reference |

**Custom Spans:**

| Span Name | contextualName | Purpose |
| :--- | :--- | :--- |
| `order.create` | `create-order` | Wraps entire order creation orchestration |
| `order.validateItems` | `validate-order-items` | Wraps product validation loop (HTTP calls) |

**Orchestration Flow:**

1. Receive order request
2. `@Observed("create-order")` span wraps entire flow
3. `@Observed("validate-order-items")` span wraps product validation:
    - For each item → `GET http://product-service:8081/products/{id}`
    - Check stock availability
4. Save order with status `PAYMENT_PENDING`
5. `POST http://payment-service:8083/payments` with orderId + totalAmount
6. Update order with paymentId and final status
7. Return order response

**Request Body (`POST /orders`):**

```json
{
  "items": [
    {
      "productId": 1,
      "quantity": 2
    },
    {
      "productId": 3
    }
  ]
}
```

### 5.3 payment-service (Port 8083)

**Purpose:** Processes payments. Includes a 150ms simulated delay to make traces visually interesting.

**Endpoints:**

| Method | Path | Description |
| :--- | :--- | :--- |
| POST | `/payments` | Process a payment |

**Data Model — `Payment`:**

| Field | Type | Description |
| :--- | :--- | :--- |
| id | Long | Auto-generated primary key |
| orderId | Long | Reference to the originating order |
| amount | BigDecimal | Payment amount |
| status | PaymentStatus | PENDING, COMPLETED, FAILED |
| createdAt | LocalDateTime | Payment creation timestamp |

**Custom Spans:**

| Span Name | contextualName | Purpose |
| :--- | :--- | :--- |
| `payment.process` | `process-payment` | Wraps payment processing (incl. 150ms sim) |

### 5.4 rag-service (Port 8084)

**Purpose:** RAG (Retrieval-Augmented Generation) pipeline — ingests project documentation into a vector store, then
answers natural language questions by retrieving relevant chunks and passing them to a local LLM.

**Endpoints:**

| Method | Path      | Description                             |
| :----- | :-------- | :-------------------------------------- |
| POST   | `/ask`    | Ask a question about the system         |
| POST   | `/ingest` | Trigger document ingestion into ChromaDB |
| GET    | `/health` | Health check                            |

**RAG Pipeline Flow:**

```
                    POST /ingest                        POST /ask
                        │                                   │
                        ▼                                   ▼
              ┌─────────────────┐                ┌─────────────────────┐
              │ IngestionService│                │  RagQueryService    │
              │                 │                │                     │
              │ 1. Load .md     │                │ 1. Retrieve chunks  │
              │    from docs/   │                │    from ChromaDB    │
              │ 2. Chunk (1000  │                │ 2. Build prompt     │
              │    chars, 200   │                │    with context     │
              │    overlap)     │                │ 3. Send to Ollama   │
              │ 3. Embed via    │                │    (llama3.2)       │
              │    Ollama       │                │ 4. Return answer    │
              │ 4. Store in     │                │                     │
              │    ChromaDB     │                └──────┬──────┬───────┘
              └───────┬─────┬── ┘                       │      │
                      │     │                   retrieve│  chat│
                 embed│     │store                      │      │
                      ▼     ▼                           ▼      ▼
              ┌──────────┐ ┌──────────┐        ┌──────────┐ ┌────────┐
              │  Ollama  │ │ ChromaDB │        │ ChromaDB │ │ Ollama │
              │(nomic-   │ │          │        │          │ │(llama  │
              │embed-text│ │          │        │          │ │ 3.2)   │
              └──────────┘ └──────────┘        └──────────┘ └────────┘
```

**Dependencies (LangChain4j BOM 1.11.0):**

| Artifact                                 | Purpose                                |
| :--------------------------------------- | :------------------------------------- |
| `langchain4j`                            | Core RAG abstractions                  |
| `langchain4j-ollama-spring-boot-starter` | Auto-configures Ollama chat + embedding|
| `langchain4j-chroma`                     | ChromaDB vector store integration      |
| `langchain4j-document-parser-apache-tika`| Document parsing (Markdown, PDF, etc.) |

**Chunking Strategy:**

- Splitter: `DocumentSplitters.recursive(1000, 200)`
- Chunk size: 1000 characters
- Overlap: 200 characters (prevents losing context at chunk boundaries)
- Retrieval: Top 5 results with minimum score 0.5

**Ollama Models:**

| Model            | Purpose    | Size   |
| :--------------- | :--------- | :----- |
| `llama3.2`       | Chat / QA  | ~2GB   |
| `nomic-embed-text` | Embeddings | ~274MB |

**Request Body (`POST /ask`):**

```json
{
  "question": "How does trace propagation work between services?"
}
```

**Response:**

```json
{
  "answer": "Trace propagation works via the W3C traceparent header...",
  "question": "How does trace propagation work between services?"
}
```

**Documents Ingested:** The project's own `DESIGN.md` and `TRACING_THOUGHT_PROCESS.md` — so you can ask the
RAG system questions about the system it's part of.

## 6. Inter-Service Communication

### 6.1 HTTP Client: Spring RestClient

We use `RestClient` (Spring Framework 6.1 / Spring Boot 3.2+) — the modern replacement for `RestTemplate`.

**Why RestClient over RestTemplate/WebClient:**

- `RestTemplate` is in maintenance mode
- `WebClient` is reactive and overkill for synchronous calls
- `RestClient` has first-class Micrometer Observation support

**Critical implementation detail:** RestClient beans are built via Spring's auto-configured `RestClient.Builder`, NOT
via `RestClient.create()`. The Spring-managed builder applies `ObservationRestClientCustomizer`, which:

1. Creates a span for every outgoing HTTP request
2. Injects the W3C `traceparent` header for trace propagation
3. Records HTTP method, URL, and status code as span attributes

### 6.2 Service URLs

Hardcoded in `order-service/application.yml` — overridden via environment variables in Docker/K8s:

| Context        | product-service URL         | payment-service URL         |
|----------------|-----------------------------|-----------------------------|
| Local (bare)   | http://localhost:8081       | http://localhost:8083       |
| Docker Compose | http://product-service:8081 | http://payment-service:8083 |
| Kubernetes     | http://product-service:8081 | http://payment-service:8083 |

Environment variables `SERVICES_PRODUCT_URL` and `SERVICES_PAYMENT_URL` override the YAML defaults.

### 6.3 DTO Strategy

Each service has its own copy of DTOs — no shared library. This avoids compile-time coupling between services (standard
microservices practice).

## 7. OpenTelemetry & Distributed Tracing

### 7.1 Dependencies (in each service POM)

```xml
<!-- AOP support for @Observed annotation -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>

        <!-- Micrometer Tracing with OpenTelemetry bridge -->
<dependency>
<groupId>io.micrometer</groupId>
<artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>

        <!-- OTLP span exporter (sends to Jaeger) -->
<dependency>
<groupId>io.opentelemetry</groupId>
<artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

### 7.2 Instrumentation Layers

```
┌──────────────────────────────────────────────────────────────┐
│                   INSTRUMENTATION STACK                      │
│                                                              │
│  Layer 1: AUTO — HTTP Server Spans                           │
│  ┌────────────────────────────────────────────────────┐      │
│  │ Spring MVC auto-creates spans for every inbound    │      │
│  │ HTTP request. No code needed.                      │      │
│  │ Span: "HTTP GET /products/{id}"                    │      │
│  └────────────────────────────────────────────────────┘      │
│                                                              │
│  Layer 2: AUTO — HTTP Client Spans                           │
│  ┌────────────────────────────────────────────────────┐      │
│  │ RestClient.Builder auto-instruments outbound HTTP. │      │
│  │ Injects traceparent header. No code needed.        │      │
│  │ Span: "HTTP GET" with url, method, status attrs    │      │
│  └────────────────────────────────────────────────────┘      │
│                                                              │
│  Layer 3: CUSTOM — @Observed Business Spans                  │
│  ┌────────────────────────────────────────────────────┐      │
│  │ @Observed on service methods creates child spans   │      │
│  │ within the HTTP server span.                       │      │
│  │ Requires: ObservedAspect bean + AOP dependency     │      │
│  │ Spans: "create-order", "validate-order-items",     │      │
│  │        "process-payment", "get-product-by-id"      │      │
│  └────────────────────────────────────────────────────┘      │
│                                                              │
│  Layer 4: LOG CORRELATION                                    │
│  ┌────────────────────────────────────────────────────┐      │
│  │ MDC pattern injects traceId + spanId into every    │      │
│  │ log line. Enables cross-service log grep.          │      │
│  │ Pattern: [service-name, traceId, spanId]           │      │
│  └────────────────────────────────────────────────────┘      │
└──────────────────────────────────────────────────────────────┘
```

### 7.3 @Observed Configuration

Each service requires two things for `@Observed` to work:

1. **Dependency:** `spring-boot-starter-aop` (enables AspectJ proxying)
2. **Bean:** `ObservedAspect` registered in an `ObservationConfig` class:

```java

@Configuration
public class ObservationConfig {
    @Bean
    public ObservedAspect observedAspect(ObservationRegistry registry) {
        return new ObservedAspect(registry);
    }
}
```

### 7.4 OTLP Export Configuration

All services export spans to Jaeger via OTLP HTTP:

```yaml
management:
  tracing:
    sampling:
      probability: 1.0                              # 100% sampling (dev)
  otlp:
    tracing:
      endpoint: http://localhost:4318/v1/traces      # Jaeger OTLP receiver
```

In Docker/K8s, the endpoint is overridden via env var:

```
MANAGEMENT_OTLP_TRACING_ENDPOINT=http://jaeger:4318/v1/traces
```

### 7.5 Complete Trace Waterfall

When `POST /orders` is called with 2 products, the trace looks like:

```
[order-service] POST /orders                                    ~400ms
  │
  ├── [order-service] create-order (@Observed)                  ~395ms
  │     │
  │     ├── [order-service] validate-order-items (@Observed)    ~50ms
  │     │     │
  │     │     ├── [CLIENT] HTTP GET /products/1                 ~20ms
  │     │     │     └── [product-service] SERVER GET /products/1
  │     │     │           └── get-product-by-id (@Observed)
  │     │     │
  │     │     └── [CLIENT] HTTP GET /products/3                 ~18ms
  │     │           └── [product-service] SERVER GET /products/3
  │     │                 └── get-product-by-id (@Observed)
  │     │
  │     └── [CLIENT] HTTP POST /payments                        ~170ms
  │           └── [payment-service] SERVER POST /payments
  │                 └── process-payment (@Observed)             ~155ms
  │                       └── 150ms simulated delay
```

**Result:** 11+ spans across 3 services, all sharing one `traceId`:

- 3 server spans (auto)
- 3 client spans (auto)
- 5 custom business spans (@Observed)

### 7.6 Log Correlation

All three services log with the same `traceId`:

```
[product-service, abc123def456, span1] Fetching product 1
[order-service,   abc123def456, span2] Creating order with 2 items
[payment-service, abc123def456, span3] Processing payment for order 1
```

## 8. Containerization

### 8.1 Docker

Each service has a minimal `Dockerfile`:

```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/<service>-0.0.1-SNAPSHOT.jar app.jar
EXPOSE <port>
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**Design choices:**

- `eclipse-temurin:21-jre-alpine` — JRE-only (not JDK), Alpine for small image (~180MB)
- JARs are pre-built with Maven, then copied in (not multi-stage — keeps it simple for learning)

### 8.2 Docker Compose

`docker-compose.yml` runs all 6 containers:

| Container       | Image                    | Ports       | Purpose                  |
|-----------------|--------------------------|-------------|--------------------------|
| jaeger          | jaegertracing/jaeger:2.4 | 16686, 4318 | Trace backend            |
| chromadb        | chromadb/chroma:latest   | 8000        | Vector store for RAG     |
| product-service | (built from Dockerfile)  | 8081        | Product catalog          |
| order-service   | (built from Dockerfile)  | 8082        | Order orchestrator       |
| payment-service | (built from Dockerfile)  | 8083        | Payment processor        |
| rag-service     | (built from Dockerfile)  | 8084        | RAG pipeline (LLM + VDB) |

**Key docker-compose design choices:**

- Service URLs use Docker DNS names (`http://product-service:8081`) via env var overrides
- OTLP endpoint overridden to `http://jaeger:4318/v1/traces`
- `depends_on` ensures Jaeger starts before services (no health check wait — acceptable for dev)

### 8.3 Kubernetes (Minikube)

Minimal K8s manifests in `k8s/` folder. Each service gets:

- **Deployment** — 1 replica, resource limits (256Mi-512Mi memory)
- **Service** — ClusterIP for inter-service communication
- **Health probes** — readiness + liveness via `/actuator/health`

**Design choices:**

- `imagePullPolicy: Never` — uses locally built Docker images (Minikube context)
- No Ingress controller — access via `kubectl port-forward` or `minikube service`
- No Helm charts — raw manifests for learning
- No PVCs — H2 in-memory is ephemeral by design
- Jaeger Service is `NodePort` for UI access

## 9. Running the System

### 9.0 Prerequisites — Ollama Setup (for rag-service)

```bash
# Install Ollama (macOS)
brew install ollama

# Start Ollama server
ollama serve

# Pull required models (in another terminal)
ollama pull llama3.2            # Chat model (~2GB)
ollama pull nomic-embed-text    # Embedding model (~274MB)

# Verify models are available
ollama list
```

### 9.1 Local (bare metal)

```bash
# Build
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
cd /Users/revanthkatanguri/Projects/galt-projects/java/incident-triage-system
mvn clean package -DskipTests

# Start Jaeger (for trace visualization)
docker run -d --name jaeger -p 16686:16686 -p 4318:4318 \
  jaegertracing/jaeger:2.4 \
  --set receivers.otlp.protocols.http.endpoint=0.0.0.0:4318

# Start ChromaDB (for vector store)
docker run -d --name chromadb -p 8000:8000 chromadb/chroma:latest

# Start Ollama (if not already running)
ollama serve &

# Start services (4 terminals)
java -jar product-service/target/product-service-0.0.1-SNAPSHOT.jar
java -jar order-service/target/order-service-0.0.1-SNAPSHOT.jar
java -jar payment-service/target/payment-service-0.0.1-SNAPSHOT.jar
java -jar rag-service/target/rag-service-0.0.1-SNAPSHOT.jar
```

### 9.2 Docker Compose

```bash
# Build JARs first
mvn clean package -DskipTests

# Start everything
docker compose up --build

# Tear down
docker compose down
```

### 9.3 Kubernetes (Minikube)

```bash
# Start Minikube
minikube start

# Point Docker to Minikube's daemon
eval $(minikube docker-env)

# Build images inside Minikube
mvn clean package -DskipTests
docker build -t product-service:latest product-service/
docker build -t order-service:latest order-service/
docker build -t payment-service:latest payment-service/
docker build -t rag-service:latest rag-service/

# Deploy
kubectl apply -f k8s/jaeger.yml
kubectl apply -f k8s/product-service.yml
kubectl apply -f k8s/order-service.yml
kubectl apply -f k8s/payment-service.yml
kubectl apply -f k8s/rag-service.yml

# Access
kubectl port-forward svc/jaeger 16686:16686      # Jaeger UI
kubectl port-forward svc/order-service 8082:8082  # Order API
kubectl port-forward svc/rag-service 8084:8084    # RAG API
```

### 9.4 Test Commands

```bash
# List products
curl http://localhost:8081/products

# Create order (triggers full distributed trace)
curl -X POST http://localhost:8082/orders \
  -H "Content-Type: application/json" \
  -d '{"items":[{"productId":1,"quantity":2},{"productId":3,"quantity":1}]}'

# View traces
open http://localhost:16686    # Jaeger UI

# --- RAG Service ---

# Ingest project documents into ChromaDB
curl -X POST http://localhost:8084/ingest

# Ask a question about the system
curl -X POST http://localhost:8084/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"How does distributed tracing work in this system?"}'

# Ask about a specific design decision
curl -X POST http://localhost:8084/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"Why did we choose RestClient over RestTemplate?"}'

# Health check
curl http://localhost:8084/health
```

## 10. Design Decisions

| Decision                                  | Rationale                                                                         |
|-------------------------------------------|-----------------------------------------------------------------------------------|
| Java records for DTOs                     | Immutable, concise, Jackson-compatible out of the box                             |
| BigDecimal for money                      | Never use double/float for financial calculations                                 |
| Denormalized order items                  | Store productName + unitPrice at time of purchase (standard e-commerce pattern)   |
| No shared DTO module                      | Each service owns its DTOs — avoids compile-time coupling                         |
| RestClient over RestTemplate              | Modern API with built-in observation/tracing support                              |
| `RestClient.Builder` (not `.create()`)    | Only the Builder gets auto-configured tracing interceptors                        |
| `@Observed` over manual Span API          | Declarative, less boilerplate, integrates with Micrometer metrics too             |
| `ObservedAspect` bean required            | Spring Boot doesn't auto-register it — explicit bean needed for @Observed to work |
| Separate validation span in order-service | Isolates product validation time from payment time in traces                      |
| 150ms payment delay                       | Makes trace waterfalls visually distinct for learning                             |
| OTLP over Zipkin protocol                 | OTLP is the OpenTelemetry standard; works with Jaeger, Tempo, any collector       |
| JRE-only Docker images                    | Smaller image size; JDK not needed at runtime                                     |
| `imagePullPolicy: Never` in K8s           | Avoids registry setup; uses local images with Minikube                            |
| Health probes on /actuator/health         | Spring Boot Actuator provides this for free; K8s uses it for pod lifecycle        |
| Hardcoded URLs + env var overrides        | Simple default for local; overridden in Docker/K8s via env vars                   |
| No OTel Collector (yet)                   | Direct app → Jaeger export is simpler for learning; Collector adds a hop          |
| LangChain4j over Spring AI                | More mature RAG abstractions (ingestor, splitters, retrievers); BOM-managed deps  |
| Ollama over OpenAI                        | Free, runs locally, no API key needed — ideal for learning                        |
| llama3.2 for chat                         | Lightweight (2GB), fast enough for local dev, good reasoning capability           |
| nomic-embed-text for embeddings           | Small (~274MB), good quality, Ollama-native                                       |
| ChromaDB over Weaviate/Pinecone           | Simplest setup (single Docker container), no auth, good for prototyping           |
| Recursive splitter (1000/200)             | 1000-char chunks balance context vs. precision; 200-char overlap prevents loss    |
| Embed own DESIGN.md + TRACING doc         | Self-referential: the system can explain itself — powerful demo                    |
| `ChatModel` (not `ChatLanguageModel`)     | LangChain4j 1.11.0 renamed the interface; `ChatModel.chat(String)` is the new API|

## 11. Future Enhancements

### Week 3 — Telemetry Ingestion into RAG
- **Jaeger API integration:** Pull traces from `http://jaeger:16686/api/traces` programmatically
- **Trace chunking strategies:** Design chunks per span, per trace, or per error pattern
- **Telemetry embeddings:** Embed traces/spans/errors into ChromaDB alongside docs
- **Natural language trace queries:** "Why was the last order slow?" answered from real trace data

### General Enhancements
- **Error handling:** Add `@ControllerAdvice` with proper HTTP error responses and error span events
- **Structured JSON logging:** Add `logstash-logback-encoder` for machine-parseable logs (RAG pipeline)
- **OTel Collector:** Add as an intermediary for routing, sampling, and enrichment
- **Database spans:** Add JDBC instrumentation for query-level tracing
- **API Gateway:** Spring Cloud Gateway as a single entry point
- **Service Discovery:** Eureka or Consul for dynamic service URLs
- **Circuit Breaker:** Resilience4j for fault tolerance on inter-service calls
- **Saga Pattern:** Handle distributed transaction rollback
- **Prometheus + Grafana:** Metrics dashboards alongside traces
- **K8s resource monitoring:** Node/pod metrics via OTel Collector for infra-level observability
- **RAG improvements:** Chat memory, multi-turn conversations, streaming responses
- **Document auto-refresh:** Re-ingest docs on file change (file watcher or scheduled task)
