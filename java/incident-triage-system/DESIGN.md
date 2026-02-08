# Incident Triage System — Design Document

## 1. Overview

A multi-module Spring Boot microservices project designed to demonstrate distributed tracing across HTTP-based inter-service communication. Three services collaborate to fulfill an order: validating products, creating orders, and processing payments.

```
                         ┌─────────────────┐
                         │   order-service  │
                         │     :8082        │
                         └────┬───────┬─────┘
               GET /products/{id}     │ POST /payments
                    ▼                 ▼
          ┌──────────────────┐  ┌──────────────────┐
          │ product-service  │  │ payment-service   │
          │     :8081        │  │     :8083         │
          └──────────────────┘  └──────────────────┘
```

## 2. Tech Stack

| Component            | Technology                          |
|----------------------|-------------------------------------|
| Framework            | Spring Boot 3.5.10                  |
| Language             | Java 21                             |
| Build Tool           | Maven (multi-module)                |
| Database             | H2 in-memory (per service)          |
| ORM                  | Spring Data JPA / Hibernate         |
| HTTP Client          | Spring RestClient (Spring 6.1+)     |
| Tracing              | Micrometer Tracing + OpenTelemetry  |
| Trace Export         | OTLP Exporter (to Jaeger/Tempo)     |

## 3. Project Structure

```
incident-triage-system/
├── pom.xml                         # Root aggregator POM
├── DESIGN.md                       # This document
│
├── product-service/                # Product catalog microservice
│   ├── pom.xml                     # Standalone POM → spring-boot-starter-parent
│   └── src/main/
│       ├── java/com/rev/triage/productservice/
│       │   ├── ProductServiceApplication.java
│       │   ├── entity/Product.java
│       │   ├── repository/ProductRepository.java
│       │   ├── service/ProductService.java
│       │   ├── controller/ProductController.java
│       │   └── dto/ProductResponse.java
│       └── resources/
│           ├── application.yml
│           └── data.sql            # 5 seed products
│
├── order-service/                  # Order orchestrator microservice
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/rev/triage/orderservice/
│       │   ├── OrderServiceApplication.java
│       │   ├── entity/Order.java
│       │   ├── entity/OrderItem.java
│       │   ├── repository/OrderRepository.java
│       │   ├── service/OrderService.java
│       │   ├── controller/OrderController.java
│       │   ├── config/RestClientConfig.java
│       │   ├── client/ProductServiceClient.java
│       │   ├── client/PaymentServiceClient.java
│       │   └── dto/
│       │       ├── CreateOrderRequest.java
│       │       ├── OrderResponse.java
│       │       ├── ProductResponse.java   # Local copy (no compile-time coupling)
│       │       ├── PaymentRequest.java
│       │       └── PaymentResponse.java
│       └── resources/
│           └── application.yml
│
└── payment-service/                # Payment processing microservice
    ├── pom.xml
    └── src/main/
        ├── java/com/rev/triage/paymentservice/
        │   ├── PaymentServiceApplication.java
        │   ├── entity/Payment.java
        │   ├── repository/PaymentRepository.java
        │   ├── service/PaymentService.java
        │   ├── controller/PaymentController.java
        │   └── dto/
        │       ├── PaymentRequest.java
        │       └── PaymentResponse.java
        └── resources/
            └── application.yml
```

## 4. Service Details

### 4.1 product-service (Port 8081)

**Purpose:** Product catalog — serves product data to other services.

**Endpoints:**
| Method | Path             | Description              |
|--------|------------------|--------------------------|
| GET    | /products        | List all products        |
| GET    | /products/{id}   | Get product by ID        |

**Data Model — `Product`:**
| Field         | Type        | Description                     |
|---------------|-------------|---------------------------------|
| id            | Long        | Auto-generated primary key      |
| name          | String      | Product name                    |
| price         | BigDecimal  | Unit price (never use double)   |
| stockQuantity | Integer     | Available inventory count       |

**Seed Data:** 5 products loaded via `data.sql` on startup (Laptop, Mouse, Keyboard, Monitor, Headphones).

### 4.2 order-service (Port 8082)

**Purpose:** Order orchestrator — validates products and triggers payments via HTTP calls to the other two services.

**Endpoints:**
| Method | Path     | Description       |
|--------|----------|-------------------|
| POST   | /orders  | Create a new order|

**Data Models:**

**`Order`:**
| Field       | Type           | Description                              |
|-------------|----------------|------------------------------------------|
| id          | Long           | Auto-generated primary key               |
| status      | OrderStatus    | CREATED → PAYMENT_PENDING → COMPLETED/FAILED |
| totalAmount | BigDecimal     | Sum of (unit_price × quantity) per item  |
| paymentId   | Long           | Foreign reference to payment-service     |
| createdAt   | LocalDateTime  | Order creation timestamp                 |
| items       | List<OrderItem>| One-to-many cascade                      |

**`OrderItem`:**
| Field       | Type       | Description                                  |
|-------------|------------|----------------------------------------------|
| id          | Long       | Auto-generated primary key                   |
| productId   | Long       | Reference to product-service product         |
| productName | String     | Denormalized (snapshot at time of purchase)   |
| quantity    | Integer    | Ordered quantity                             |
| unitPrice   | BigDecimal | Denormalized (snapshot at time of purchase)   |
| order       | Order      | ManyToOne back-reference                     |

**Request Body (`POST /orders`):**
```json
{
  "items": [
    { "productId": 1, "quantity": 2 },
    { "productId": 3, "quantity": 1 }
  ]
}
```

**Orchestration Flow:**
1. Receive order request
2. For each item → `GET http://localhost:8081/products/{id}` (validate product exists + check stock)
3. Calculate total amount
4. Save order with status `PAYMENT_PENDING`
5. `POST http://localhost:8083/payments` with orderId + totalAmount
6. Update order with paymentId and final status (`COMPLETED` or `FAILED`)
7. Return order response

### 4.3 payment-service (Port 8083)

**Purpose:** Processes payments. Includes a 150ms simulated delay to make traces visually interesting.

**Endpoints:**
| Method | Path       | Description         |
|--------|------------|---------------------|
| POST   | /payments  | Process a payment   |

**Data Model — `Payment`:**
| Field     | Type          | Description                        |
|-----------|---------------|------------------------------------|
| id        | Long          | Auto-generated primary key         |
| orderId   | Long          | Reference to the originating order |
| amount    | BigDecimal    | Payment amount                     |
| status    | PaymentStatus | PENDING, COMPLETED, FAILED         |
| createdAt | LocalDateTime | Payment creation timestamp         |

## 5. Inter-Service Communication

### 5.1 HTTP Client: Spring RestClient

We use `RestClient` (introduced in Spring Framework 6.1 / Spring Boot 3.2) — the modern replacement for `RestTemplate`.

**Why RestClient over RestTemplate/WebClient:**
- `RestTemplate` is in maintenance mode
- `WebClient` is reactive and overkill for synchronous calls
- `RestClient` has first-class Micrometer Observation support

**Critical implementation detail:** RestClient beans are built via Spring's auto-configured `RestClient.Builder` (injected in `RestClientConfig.java`), NOT via `RestClient.create()`. The Spring-managed builder automatically applies `ObservationRestClientCustomizer`, which:
1. Creates a span for every outgoing HTTP request
2. Injects the W3C `traceparent` header for trace propagation
3. Records HTTP method, URL, and status code as span attributes

```java
@Bean
public RestClient productRestClient(RestClient.Builder builder) {
    return builder
            .baseUrl(productServiceUrl)    // from application.yml
            .build();
}
```

### 5.2 Service URLs

Hardcoded in `order-service/application.yml` (no service discovery):
```yaml
services:
  product:
    url: http://localhost:8081
  payment:
    url: http://localhost:8083
```

### 5.3 DTO Strategy

Each service has its own copy of DTOs for deserialization — no shared library. This avoids compile-time coupling between services, which is the standard microservices practice. Each service can evolve its API independently.

## 6. OpenTelemetry & Distributed Tracing

### 6.1 Dependencies (in each service POM)

```xml
<!-- Micrometer Tracing with OpenTelemetry bridge -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>

<!-- OTLP span exporter -->
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

### 6.2 How It Works

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Tracing Architecture                             │
│                                                                     │
│  Spring Boot Auto-Configuration                                    │
│  ┌─────────────────────────────────────┐                           │
│  │ micrometer-tracing-bridge-otel      │                           │
│  │  • Creates Micrometer Tracer        │                           │
│  │  • Bridges to OpenTelemetry SDK     │                           │
│  │  • Auto-instruments:               │                           │
│  │    - Inbound HTTP (server spans)    │                           │
│  │    - Outbound RestClient (client)   │                           │
│  │    - JPA / JDBC queries             │                           │
│  └──────────────┬──────────────────────┘                           │
│                 │                                                   │
│  ┌──────────────▼──────────────────────┐                           │
│  │ opentelemetry-exporter-otlp         │                           │
│  │  • Exports spans via OTLP/HTTP      │                           │
│  │  • Default: localhost:4318          │                           │
│  │  • Compatible with:                │                           │
│  │    - Jaeger                        │                           │
│  │    - Grafana Tempo                 │                           │
│  │    - Any OTLP collector            │                           │
│  └─────────────────────────────────────┘                           │
└─────────────────────────────────────────────────────────────────────┘
```

**Spring Boot auto-configures everything** — no manual `@Bean` definitions for tracing are needed. The presence of `micrometer-tracing-bridge-otel` on the classpath triggers:
- `ObservationAutoConfiguration` — creates the `ObservationRegistry`
- `MicrometerTracingAutoConfiguration` — creates the `Tracer`
- `ObservationRestClientCustomizer` — instruments all `RestClient.Builder` beans

### 6.3 Configuration (per service `application.yml`)

```yaml
management:
  tracing:
    sampling:
      probability: 1.0       # Sample 100% of traces (dev mode)

logging:
  pattern:
    correlation: "[${spring.application.name:},%X{traceId:-},%X{spanId:-}] "
```

### 6.4 Trace Propagation Flow

When `POST /orders` is called with 2 products:

```
[order-service] POST /orders                          ← Root span (server)
  │
  ├── [order-service → product-service]               ← Client span
  │     GET /products/1
  │     Header: traceparent: 00-{traceId}-{spanId}-01
  │     └── [product-service] GET /products/1         ← Server span (same traceId)
  │
  ├── [order-service → product-service]               ← Client span
  │     GET /products/3
  │     Header: traceparent: 00-{traceId}-{spanId}-01
  │     └── [product-service] GET /products/3         ← Server span (same traceId)
  │
  └── [order-service → payment-service]               ← Client span
        POST /payments
        Header: traceparent: 00-{traceId}-{spanId}-01
        └── [payment-service] POST /payments          ← Server span (same traceId)
              └── 150ms simulated processing
```

**Result:** 7+ spans across 3 services, all sharing the same `traceId`.

### 6.5 Log Correlation

All three services log with the same `traceId`, making it possible to grep across service logs:

```
[product-service,abc123def456,span1] Fetching product 1
[order-service,abc123def456,span2]   Creating order with 2 items
[payment-service,abc123def456,span3] Processing payment for order 1
```

## 7. Data Storage

Each service has its own isolated H2 in-memory database:

| Service          | JDBC URL                | Console                          |
|------------------|-------------------------|----------------------------------|
| product-service  | jdbc:h2:mem:productdb   | http://localhost:8081/h2-console |
| order-service    | jdbc:h2:mem:orderdb     | http://localhost:8082/h2-console |
| payment-service  | jdbc:h2:mem:paymentdb   | http://localhost:8083/h2-console |

- `ddl-auto: create-drop` — schema created on startup, dropped on shutdown
- Product-service loads 5 seed products via `data.sql`
- All use `sa` user with no password

## 8. Design Decisions

| Decision | Rationale |
|----------|-----------|
| Java records for DTOs | Immutable, concise, Jackson-compatible out of the box |
| BigDecimal for money | Never use double/float for financial calculations |
| Denormalized order items | Store productName + unitPrice at time of purchase (standard e-commerce pattern) |
| No shared DTO module | Each service owns its DTOs — avoids compile-time coupling between services |
| Hardcoded service URLs | Simple for dev; easily overridden via env vars for deployment |
| 150ms payment delay | Makes trace waterfalls visually interesting for demos |
| RestClient over RestTemplate | Modern API with built-in observation/tracing support |
| No service discovery | Keeps the setup simple — focus is on tracing, not infrastructure |
| Standalone POMs (not child modules) | Each service has its own `spring-boot-starter-parent`; root POM is just an aggregator |

## 9. Future Enhancements

- **Error handling:** Add `@ControllerAdvice` with proper HTTP error responses
- **Jaeger/Tempo:** Add `docker-compose.yml` for a local tracing backend
- **API Gateway:** Add Spring Cloud Gateway as a single entry point
- **Service Discovery:** Eureka or Consul for dynamic service URLs
- **Circuit Breaker:** Resilience4j for fault tolerance on inter-service calls
- **Stock Decrement:** Actually reduce stock in product-service on order
- **Saga Pattern:** Handle distributed transaction rollback (e.g., payment fails after order created)
