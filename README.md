# EventFlow Starter

A high-performance CDC event processing framework built on Spring Boot, designed for OceanBase CDC + Kafka scenarios. Reusable across 50+ business projects with zero boilerplate.

## Architecture

**Ports & Adapters (Hexagonal Architecture)** — core engine has zero Kafka dependency. Business code is pure POJO with zero OTel dependency.

```
┌─────────────────────────────────────────────────────┐
│              Inbound Adapters                        │
│   KafkaInboundAdapter / REST API / Test Inbound     │
├─────────────────────────────────────────────────────┤
│                  Core Engine                         │
│   Envelope → IdempotentFilter → Router              │
│   → PipelineRegistry → Dispatcher → Pipeline        │
│       ├─ PreSteps (enrich, validate, audit)         │
│       ├─ EventHandler (business logic)              │
│       └─ OutboundPublisher (fan-out)                │
├─────────────────────────────────────────────────────┤
│              Outbound Adapters                       │
│   KafkaOutbound / LoggingOutbound / Custom          │
└─────────────────────────────────────────────────────┘
```

## Key Features

| Feature | Description |
|---------|-------------|
| **Business Simplicity** | One annotation + one interface = done. 10 lines of code to process CDC events |
| **Dual-Mode Dispatch** | Ordered (per-key serial) + Concurrent (shared pool) with automatic routing |
| **Idempotent Dedup** | Caffeine-based dedup filter, auto-traced in pipeline |
| **Backpressure** | High/low water mark with Kafka pause/resume, prevents OOM |
| **Auto-Tracing** | Full OpenTelemetry tracing at framework level — business code has ZERO OTel dependency |
| **Metrics** | Micrometer + Prometheus metrics, Grafana dashboard included |
| **Pipeline Steps** | Composable pre-processing chain (enrich → validate → audit → handler) |
| **DLT Support** | Poison messages automatically routed to Dead Letter Topic |
| **Test Scaffold** | Test without real Kafka — mock inbound, capture outbound |

## Modules

```
eventflow/
├── eventflow-core                  # Core engine, models, dispatcher, tracing
├── eventflow-kafka                 # Kafka inbound/outbound adapters
├── eventflow-spring-boot-starter   # Auto-configuration, @EventPipeline annotation
├── eventflow-test                  # Test scaffolding (fixtures, capture ports)
├── eventflow-sample                # Demo app with REST API, load testing, observability
└── docs/                           # Design documents (CN + EN) with architecture diagrams
```

## Quick Start

### Prerequisites

- Java 17+
- Maven 3.8+

### Build

```bash
mvn clean install -DskipTests
```

### Run the Sample App

```bash
# Start Jaeger (tracing)
# Download from https://github.com/jaegertracing/jaeger/releases
jaeger-all-in-one --collector.otlp.enabled=true

# Start the sample app
cd eventflow-sample
mvn spring-boot:run -Dspring-boot.run.arguments="--demo.auto-run=false"
```

### Try It Out

```bash
# Submit an order event
curl -X POST http://localhost:8080/api/order \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-1001","op":"INSERT","status":"CREATED"}'

# Submit a payment (goes through 3 pre-steps: exchange rate → fraud check → compliance)
curl -X POST http://localhost:8080/api/payment \
  -H "Content-Type: application/json" \
  -d '{"paymentId":"PAY-2001","op":"INSERT","amount":25000,"status":"CREATED"}'

# Load test: 500 events, 50 concurrent
curl -X POST http://localhost:8080/api/load-test \
  -H "Content-Type: application/json" \
  -d '{"eventCount":500,"table":"t_order","concurrency":50}'

# Mixed load test (order + payment)
curl -X POST http://localhost:8080/api/load-test/mixed \
  -H "Content-Type: application/json" \
  -d '{"eventCount":200,"concurrency":20}'

# Real-time metrics
curl http://localhost:8080/api/metrics
```

API responses include `traceId` and `jaegerUrl` for direct trace lookup.

## Business Code Example

Business handlers have **zero framework dependency** — pure POJOs:

```java
@Component
@EventPipeline(routeKey = "t_order:*", ordered = true)
public class OrderCdcHandler implements EventHandler {

    @Override
    public ProcessingResult handle(Envelope envelope) {
        String op = envelope.getHeaders().get("op");
        String pk = envelope.getHeaders().get("pk");

        if ("DELETE".equals(op)) {
            return new ProcessingResult.Filtered("skip delete");
        }

        return new ProcessingResult.Success(List.of(
            new Destination("kafka", "order-sync-out", Map.of("orderId", pk, "action", op))
        ));
    }
}
```

All tracing (idempotent check, pre-steps, handler, outbound publish, offset tracking) is **automatic at the framework Dispatcher level**. Every project using this starter gets full OpenTelemetry tracing for free.

## Observability Stack

| Service | URL | Purpose |
|---------|-----|---------|
| Spring Boot App | http://localhost:8080 | EventFlow application |
| Jaeger UI | http://localhost:16686 | Distributed tracing |
| Prometheus | http://localhost:9090 | Metrics collection |
| Grafana | http://localhost:3000 | Dashboard (admin/admin) |

### Auto-Traced Span Tree (per event)

```
eventflow.pipeline.execute
  ├─ eventflow.idempotent.check        (dedup key, duplicate yes/no)
  ├─ eventflow.pre-steps
  │   ├─ pre-step[0] ExchangeRateEnrichStep
  │   ├─ pre-step[1] FraudCheckStep
  │   └─ pre-step[2] ComplianceAuditStep
  ├─ eventflow.handler.handle          (handler class, op, pk, result)
  ├─ eventflow.outbound.publish
  │   ├─ send → kafka:payment-sync-out
  │   └─ send → kafka:payment-audit-log
  └─ eventflow.offset.complete         (offset, committable, inflight)
```

### Grafana Dashboard

Pre-built dashboard with 15 panels:
- **Trace Lookup**: Paste a `traceId` to view full trace in Grafana
- **Recent Traces**: Browse and click to expand
- **HTTP Metrics**: Request rate, p95/p99 latency
- **Pipeline Metrics**: Event throughput, duration by routeKey
- **JVM Metrics**: Heap memory, threads, GC pause, CPU usage

### Performance Test Results

| Scenario | Events | Concurrency | Throughput | P50 | P95 | P99 |
|----------|--------|-------------|------------|-----|-----|-----|
| Pure Order (lightweight) | 500 | 50 | **1082 events/sec** | 2.4ms | 5.4ms | 11.1ms |
| Order + Payment (mixed) | 200 | 20 | **52.7 events/sec** | 2.6ms | 312ms | 321ms |

Payment is slower due to 3 pre-steps with simulated API latency (50ms+30ms+20ms).

## Configuration

```yaml
eventflow:
  enabled: true
  dispatch:
    ordered-worker-count: 16    # Ordered worker slots
    concurrent-core-size: 8     # Concurrent pool core threads
    concurrent-max-size: 32     # Concurrent pool max threads
  backpressure:
    high-water-mark: 1000       # Pause consumer when inflight >= this
    low-water-mark: 500         # Resume consumer when inflight <= this
  idempotent:
    cache-size: 100000
    ttl: 10m
  kafka:
    bootstrap-servers: localhost:9092
    group-id: my-app-eventflow
    topics:
      - cdc_topic
```

## Design Documents

Full technical design documents are available in the [docs/](docs/) directory:

- [EventFlow-Design-CN.md](docs/EventFlow-Design-CN.md) — Chinese version
- [EventFlow-Design-EN.md](docs/EventFlow-Design-EN.md) — English version
- Architecture diagrams (Mermaid + PNG)

## Tech Stack

- Java 17, Spring Boot 3.5
- OpenTelemetry SDK (tracing), Micrometer (metrics)
- Caffeine (idempotent cache)
- Apache Kafka (inbound/outbound)
- Jaeger, Prometheus, Grafana (observability)
