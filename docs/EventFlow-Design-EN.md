# EventFlow Starter — Technical Design Document

> A Spring Boot-based message/event processing framework for OceanBase CDC + Kafka scenarios, designed for reuse across 50+ business projects.

---

## Table of Contents

1. [Background & Objectives](#1-background--objectives)
2. [Architecture Design](#2-architecture-design)
3. [Concurrency Model](#3-concurrency-model)
4. [Maven Project Structure](#4-maven-project-structure)
5. [Core Models & Interfaces](#5-core-models--interfaces)
6. [Advanced Mechanisms](#6-advanced-mechanisms)
7. [Spring Boot Starter Assembly](#7-spring-boot-starter-assembly)
8. [Configuration Reference](#8-configuration-reference)
9. [Testing Scaffold](#9-testing-scaffold)
10. [Business Integration Example](#10-business-integration-example)
11. [Identified Risks & Fixes](#11-identified-risks--fixes)
12. [Evolution Roadmap](#12-evolution-roadmap)

---

## 1. Background & Objectives

### 1.1 Current Pain Points

- **Mixed data streams**: OceanBase CDC syncs change data from multiple physical tables into a single Kafka topic. Consumers must implement complex event identification and routing themselves.
- **Tight coupling**: Business code directly depends on Kafka SDK, making it costly to add new inbound channels (e.g., HTTP) later.
- **Concurrency challenges**: Single-partition data volumes are extremely high. Single-threaded consumption has insufficient throughput; naive multi-threaded consumption causes out-of-order processing and offset loss.
- **Reinventing the wheel**: Non-functional concerns (deduplication, backpressure, DLT, graceful shutdown) are reimplemented across projects with inconsistent quality.

### 1.2 Design Goals

| Goal | Description |
|------|-------------|
| **Minimal business code** | One annotation + one interface implementation; ~10 lines of code to onboard CDC processing |
| **Anti-corruption architecture** | Hexagonal architecture; core engine has zero Kafka dependency; zero business code changes when switching MQ or adding inbound channels |
| **Concurrency safety** | Ordered/Concurrent dual-mode + OffsetTracker sliding window; eliminates data loss and out-of-order processing |
| **Adaptive backpressure** | Precise backpressure via pause/resume; prevents OOM at the source |
| **Observability** | Built-in OpenTelemetry Tracing + Micrometer Metrics out of the box |
| **Testability** | Testing scaffold enables Pipeline verification without real Kafka |
| **MVP-first** | First version: functional, reusable, verifiable, continuously evolvable |

---

## 2. Architecture Design

### 2.1 Architecture Pattern

Adopts the **Ports & Adapters (Hexagonal Architecture)** pattern with three layers:

```
┌─────────────────────────────────────────────────────┐
│                 Inbound Adapters                     │
│   KafkaInboundAdapter / HttpInbound / TestInbound   │
├─────────────────────────────────────────────────────┤
│                   Core Engine                        │
│   EnvelopeConverter → IdempotentFilter → Router     │
│   → PipelineRegistry → Dispatcher → Pipeline        │
│   → ProcessingResult                                 │
│                                                      │
│   OffsetTracker + BackpressureController             │
├─────────────────────────────────────────────────────┤
│                Outbound Adapters                     │
│   KafkaOutbound / HttpOutbound / DltPublisher       │
└─────────────────────────────────────────────────────┘
```

### 2.2 Core Principles

1. **Anti-corruption layer**: The core engine defines only interface contracts (Ports); it never imports Kafka/HTTP-specific SDKs.
2. **Unified event model**: All inbound data is converted to an Envelope (following CloudEvents specification).
3. **SPI extensibility**: Router, IdempotentFilter, and OutboundPort are all SPI interfaces with replaceable implementations.

### 2.3 Diagram Index

| Diagram | File | Description |
|---------|------|-------------|
| Hexagonal Architecture Overview | `01-architecture-overview.mmd` | Three-layer structure + component relationships + data flow |
| Parallel Consumer Sequence | `02-parallel-consumer-flow.mmd` | Complete interaction sequence across 6 phases |
| Core Class Relationships | `03-class-design.mmd` | Interfaces, models, adapters class diagram |
| Module Dependencies | `04-module-dependency.mmd` | Maven inter-module dependency graph |
| Offset Window State | `05-offset-tracker-state.mmd` | Sliding window scan and watermark calculation |
| Backpressure State Machine | `06-backpressure-lifecycle.mmd` | RUNNING/PAUSED transitions + hysteresis |

---

## 3. Concurrency Model

### 3.1 Dual-Mode Design

The framework provides two concurrency modes, declared per Pipeline and dispatched uniformly:

| Dimension | Ordered Mode | Concurrent Mode |
|-----------|--------------|-----------------|
| Use case | Same primary key must be processed serially (e.g., account balance) | No ordering dependency; maximum throughput (e.g., log sync) |
| Thread model | N SingleThreadExecutors forming a Worker matrix | Shared ThreadPoolExecutor |
| Dispatch strategy | `hash(table + pk) % N` → pinned to a specific Worker | Direct submit to thread pool |
| Ordering guarantee | Strict ordering per table+pk | No ordering guarantee |
| Declaration | `@EventPipeline(ordered = true)` | `@EventPipeline(ordered = false)` |

### 3.2 Ordered Mode Flow

```
poll() → deserialize → dedup → Router → RouteKey
  → PipelineRegistry match (ordered=true)
  → hash(table+pk) % N → Worker[i] serial execution
  → Pipeline (filter → transform → enrich → handler)
  → OutboundPublisher.publish()
  → OffsetTracker.complete()
```

### 3.3 Concurrent Mode Flow

```
poll() → deserialize → dedup → Router → RouteKey
  → PipelineRegistry match (ordered=false)
  → SharedThreadPool concurrent execution
  → Pipeline (filter → transform → enrich → handler)
  → OutboundPublisher.publish()
  → OffsetTracker.complete()
```

### 3.4 Key Constraints

- Both modes share the same OffsetTracker and backpressure control logic.
- Offset safety and backpressure capability are mode-independent and fully consistent.
- A single message can fan out to multiple Pipelines (potentially mixing Ordered and Concurrent). OffsetTracker uses reference counting to ensure offset advancement only after all Pipelines complete.

---

## 4. Maven Project Structure

```
eventflow/
├── pom.xml                              # Parent BOM
│
├── eventflow-core/                      # Core engine (zero external dependencies)
│   ├── model/                           #   Envelope, RouteKey, ProcessingResult, Destination
│   ├── port/                            #   InboundPort, OutboundPort (SPI)
│   ├── routing/                         #   Router, PipelineRegistry
│   ├── pipeline/                        #   Pipeline, PipelineStep
│   ├── dispatch/                        #   Dispatcher, OffsetTracker, BackpressureController
│   ├── idempotent/                      #   IdempotentFilter (SPI)
│   ├── observe/                         #   EventFlowTracer (OTel abstraction)
│   └── annotation/                      #   @EventPipeline, @EventHandler
│
├── eventflow-kafka/                     # Kafka adapter
│   ├── KafkaInboundAdapter.java         #   With pause/resume backpressure
│   ├── KafkaOutboundAdapter.java
│   ├── KafkaEnvelopeConverter.java      #   CDC JSON → Envelope
│   └── KafkaDltPublisher.java           #   Poison message bypass
│
├── eventflow-spring-boot-starter/       # Auto-configuration
│   ├── EventFlowAutoConfiguration.java
│   ├── EventFlowProperties.java
│   └── EventFlowRegistrar.java          #   Scans @EventPipeline
│
├── eventflow-test/                      # Testing scaffold
│   ├── @EventProcessingTest
│   ├── MockInboundPort.java
│   ├── CaptureOutboundPort.java
│   └── EnvelopeFixtures.java
│
└── eventflow-sample/                    # Usage example
```

### 4.1 Dependency Graph

| Module | Dependencies | Notes |
|--------|-------------|-------|
| `eventflow-core` | slf4j-api, jackson-core; optional: otel-api, micrometer-core | Zero MQ dependency |
| `eventflow-kafka` | eventflow-core + kafka-clients | Implements InboundPort / OutboundPort |
| `eventflow-spring-boot-starter` | eventflow-core + eventflow-kafka + spring-boot-autoconfigure | Auto-configuration |
| `eventflow-test` | eventflow-core + spring-boot-test | No Kafka dependency |

---

## 5. Core Models & Interfaces

### 5.1 Envelope (Unified Event Model)

Based on the CloudEvents specification; all inbound data is uniformly converted to this model:

| Field | Type | Description |
|-------|------|-------------|
| id | String | Unique ID, default UUID |
| source | String | Origin identifier, e.g., `cdc://oceanbase` |
| type | String | Event type, e.g., `cdc.row_change` |
| time | Instant | Event timestamp |
| subject | String | Business key, e.g., `order:12345` |
| data | JsonNode | Business payload (CDC row data) |
| headers | Map\<String, String\> | Extension headers: table, op, pk, txId, etc. |
| sourceMeta | SourceMeta | Framework-internal: topic, partition, offset |

**Design constraint**: Envelope must be immutable. All fields are final; modifications return new instances via `withData()` / `withHeader()`. Fan-out scenarios pass deepCopy to each Pipeline to prevent concurrent mutation.

### 5.2 RouteKey

```
RouteKey(table="t_order", operation="INSERT")
```

Supports wildcard matching: `t_order:*` matches all operation types.

### 5.3 ProcessingResult (Sealed Interface)

| Subtype | Meaning | Subsequent Action |
|---------|---------|-------------------|
| Success(List\<Destination\>) | Processing succeeded | Send to target Destinations, then complete |
| Filtered(String reason) | Filtered out | Complete immediately |
| Failed(Throwable, boolean retryable) | Processing failed | Send to DLT; complete if DLT succeeds; stall window + alert if DLT also fails |

### 5.4 Core Interface Catalog

| Interface | Responsibility | Default Implementation |
|-----------|---------------|----------------------|
| Router | Envelope → RouteKey | HeaderBasedRouter (extracts table/op from headers) |
| PipelineStep | Single processing step (filter/transform/enrich) | Business-defined |
| EventHandler | Core business logic entry point | Business implementation |
| PipelineRegistry | RouteKey → List\<PipelineDefinition\> | Built-in, auto-registration |
| IdempotentFilter | Deduplication check | CaffeineIdempotentFilter (MVP), Redis (future) |
| OutboundPort | Outbound publishing | KafkaOutboundAdapter, HttpOutboundAdapter |
| InboundPort | Inbound receiving | KafkaInboundAdapter, MockInboundPort |

---

## 6. Advanced Mechanisms

### 6.1 OffsetTracker (High-Watermark Sliding Window)

**Problem**: Under concurrent consumption, higher-offset messages may complete before lower-offset ones. Directly committing the higher offset would cause data loss for uncommitted lower offsets upon application crash.

**Solution**: One OffsetTracker instance per TopicPartition, maintaining a sliding window:

1. **Register**: Before dispatch, `register(offset, pipelineCount)` creates a window entry with a reference count.
2. **Complete**: After each Pipeline finishes, `complete(offset)` decrements the reference count by 1.
3. **Commit**: Scans from the lowest offset; only commits the max offset + 1 of the contiguous zero-count region.

**Example**:

```
Window state: [100:done] [101:done] [102:pending(2)] [103:done] [104:pending(1)]

Scan process:
  100 → done → remove
  101 → done → remove
  102 → pending → STOP!

committableOffset = 102 (i.e., 101+1, Kafka commit semantics)
inflight = 3 (offsets 102, 103, 104 remain in window)
```

**Implementation choice**: ConcurrentSkipListMap + AtomicInteger. register/committableOffset are called only by the poll thread (single-thread safe); complete is called by Worker threads (CAS atomic decrement). Zero lock contention.

### 6.2 Backpressure Control

**Problem**: If consumption outpaces processing, inflight messages accumulate unboundedly, causing OOM.

**Solution**: Precise backpressure via Kafka's native pause/resume API:

| State | Trigger | Action |
|-------|---------|--------|
| RUNNING → PAUSED | inflight >= highWaterMark (default 1000) | `consumer.pause(partition)` |
| PAUSED → RUNNING | inflight <= lowWaterMark (default 500) | `consumer.resume(partition)` |

**Hysteresis gap** (high=1000, low=500): Prevents rapid pause/resume oscillation near the threshold. If pause and resume thresholds were identical (e.g., both 1000), inflight fluctuating between 999 and 1000 would generate excessive pause/resume calls.

**Key design decision**: pause/resume is the sole flow-control mechanism. The Dispatcher thread pool uses no rejection policy (unbounded queue), preventing two backpressure systems from conflicting.

### 6.3 Dynamic Concurrent Dispatch (Dispatcher)

- **Ordered Mode**: Maintains N SingleThreadExecutors (default N=16). Routes same-primary-key messages to the same Worker via `hash(table + pk) % N`, guaranteeing local serial execution.
- **Concurrent Mode**: Shared ThreadPoolExecutor (core=8, max=32). Messages are submitted directly with no ordering guarantees for maximum throughput.
- **Strategy declaration**: Via `@EventPipeline(ordered=true/false)` annotation or configuration file.

### 6.4 Deduplication (Idempotent Filter)

- Default strategy: Generates dedup key from `table + pk + timestamp` or `txId`.
- MVP implementation: Caffeine local cache (100K entries, 10-minute TTL).
- SPI extension: Can be replaced with Redis implementation for cross-instance sharing.
- **Known limitation**: Local cache is lost on restart and not shared across instances. Documentation must clearly communicate this to users.

### 6.5 Poison Message Handling

JSON deserialization uses lenient mode. Completely unparseable messages:
1. Are bypassed directly to DLT (Dead Letter Topic) at the Inbound layer.
2. Are immediately registered + completed in OffsetTracker to prevent window stalling.
3. Must never enter infinite retry loops.

### 6.6 Graceful Shutdown

Execution order:
1. `consumer.pause(ALL)` — Cut off data source
2. `running = false` — Exit poll loop
3. `dispatcher.shutdown(timeout)` — Wait for Worker queues to drain
4. `commitCompletedOffsets()` — Final commit
5. `consumer.close()` — Release connection
6. Spring container continues destroying other beans

### 6.7 Rebalance Handling

During `onPartitionsRevoked`:
1. Block-wait for revoked partitions' inflight messages to complete (awaitDrain, 10-second timeout).
2. Commit final offset for those partitions.
3. Clean up Tracker and pause state.

---

## 7. Spring Boot Starter Assembly

### 7.1 Auto-Configuration

`EventFlowAutoConfiguration` uses `@AutoConfiguration` + `@ConditionalOnClass`:

- Default assembly: HeaderBasedRouter, CaffeineIdempotentFilter, PipelineRegistry, Dispatcher, CompositeOutboundPublisher
- When kafka-clients is on the classpath: auto-assembles KafkaInboundAdapter, KafkaOutboundAdapter
- All core beans are annotated with `@ConditionalOnMissingBean`, allowing business override

### 7.2 Pipeline Auto-Registration

`EventFlowRegistrar` implements `SmartInitializingSingleton`. On startup, it scans all beans annotated with `@EventPipeline` and auto-registers them into PipelineRegistry.

---

## 8. Configuration Reference

```yaml
eventflow:
  enabled: true

  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP:localhost:9092}
    consumer:
      group-id: ${spring.application.name}-eventflow
      auto-offset-reset: earliest
      enable-auto-commit: false           # Framework manages offset commits
      max-poll-records: 500
    topics:
      - cdc_oceanbase_main
    dlt:
      topic-suffix: .DLT
      enabled: true

  dispatch:
    ordered-worker-count: 16              # Ordered mode Worker count
    concurrent-core-size: 8               # Concurrent core threads
    concurrent-max-size: 32               # Concurrent max threads

  backpressure:
    high-water-mark: 1000                 # inflight >= this → pause
    low-water-mark: 500                   # inflight <= this → resume

  idempotent:
    enabled: true
    cache-size: 100000
    ttl: 10m

  shutdown:
    timeout-seconds: 30

  observability:
    tracing-enabled: true
    metrics-enabled: true
```

---

## 9. Testing Scaffold

### 9.1 Design Philosophy

Business teams must be able to verify Pipeline logic without starting Kafka. The framework provides:

| Component | Responsibility |
|-----------|---------------|
| `@EventProcessingTest` | Composite annotation: loads Spring context, disables Kafka, injects mock components |
| `MockInboundPort` | Directly injects Envelope, synchronously executes Pipeline, returns ProcessingResult |
| `CaptureOutboundPort` | Captures all output Destinations for assertion |
| `EnvelopeFixtures` | Quickly constructs CDC INSERT/UPDATE/DELETE test events |

### 9.2 Usage Pattern

```
@EventProcessingTest on test class
→ Auto-injects MockInboundPort + CaptureOutboundPort
→ Construct events with EnvelopeFixtures
→ inbound.send(event) triggers Pipeline
→ Assert result and outbound.getCaptured()
```

No Testcontainers required. No Embedded Kafka. Sub-second execution.

---

## 10. Business Integration Example

Complete business-side onboarding steps:

**Step 1**: Add dependency `eventflow-spring-boot-starter`

**Step 2**: Write Handler (this is all the business code)

```java
@EventPipeline(routeKey = "t_order:*", ordered = true)
public class OrderCdcHandler implements EventHandler {

    @Override
    public ProcessingResult handle(Envelope envelope) {
        String op = envelope.getHeaders().get("op");
        if ("DELETE".equals(op)) {
            return new ProcessingResult.Filtered("skip delete");
        }
        // Business processing...
        return new ProcessingResult.Success(List.of(
            new Destination("kafka", "order-sync-out", payload)
        ));
    }
}
```

**Step 3**: Configure application.yml (Kafka connection + tuning as needed)

**Done.** No Kafka Consumer code. No offset management. No thread pool implementation. No backpressure handling.

---

## 11. Identified Risks & Fixes

### 11.1 P0 Level (Must fix in MVP)

| # | Risk | Impact | Fix |
|---|------|--------|-----|
| 1 | Fan-out causes premature offset commit | Data loss | OffsetTracker with reference counting; register declares pipeline count; offset advances only when all pipelines complete |
| 2 | CallerRunsPolicy blocks poll thread | All partitions stall, triggers rebalance | Remove thread pool rejection policy; pause/resume as sole backpressure mechanism |
| 3 | No rebalance handling for in-flight messages | Data loss + duplicates | Implement ConsumerRebalanceListener; awaitDrain + commit final offset on revoke |
| 4 | Failed messages unconditionally marked complete | Data loss | Complete only after successful DLT delivery; stall window + alert if DLT also fails |

### 11.2 P1 Level (Recommended for MVP)

| # | Risk | Impact | Fix |
|---|------|--------|-----|
| 5 | OffsetTracker lock contention | Performance bottleneck at high throughput | ConcurrentSkipListMap + AtomicInteger lock-free design |
| 6 | Excessively frequent offset commits | Broker pressure | Time-interval commits (e.g., every 5 seconds) |
| 7 | Ordered hash skew invisible | Hot Worker overload | Expose per-Worker queue depth metrics |
| 8 | Envelope mutability concurrency risk | Dirty data | Immutable design + deepCopy on fan-out |
| 9 | Outbound failure after offset committed | Data swallowed | Publish before complete |
| 10 | Missing metrics | Unable to operate in production | Built-in Micrometer instrumentation (see table below) |

### 11.3 MVP Required Metrics

| Metric Name | Type | Labels |
|-------------|------|--------|
| eventflow.inflight | Gauge | partition |
| eventflow.offset.committed | Counter | partition |
| eventflow.pipeline.duration | Timer | routeKey, status |
| eventflow.pipeline.error | Counter | routeKey, errorType |
| eventflow.dispatch.queue.size | Gauge | mode, slot |
| eventflow.backpressure.pause | Counter | partition |
| eventflow.dlt.sent | Counter | topic, reason |
| eventflow.idempotent.hit | Counter | — |

### 11.4 Architecture-Level Notes

- **Single poll thread bottleneck**: Reserve the ability to split into one KafkaInboundAdapter instance per topic. MVP can use a single thread, but the architecture must not hard-code this constraint.
- **IdempotentFilter limitations**: Caffeine local cache is lost on restart and not shared across instances. Documentation must clearly state this; Redis SPI must be reserved.

---

## 12. Evolution Roadmap

| Phase | Duration | Goal | Deliverables |
|-------|----------|------|-------------|
| **P0: MVP** | 4 weeks | End-to-end core pipeline | core + kafka + starter + test modules. Dual-mode Dispatcher, ref-counted OffsetTracker, pause/resume backpressure, DLT, Caffeine dedup, @EventPipeline annotation-driven, @EventProcessingTest scaffold. Internal pilot with 2-3 projects. |
| **P1: Hardening** | 3 weeks | Production reliability | Full OpenTelemetry integration (Span + Metrics + MDC traceId). Grafana dashboard templates. End-to-end graceful shutdown verification. Optional Redis dedup backend. Pipeline Step chaining DSL. |
| **P2: Multi-Inbound** | 3 weeks | Multiple inbound channels | eventflow-http module: HTTP Controller → Envelope → same Pipeline. Unified API authentication. |
| **P3: Multi-MQ** | 3 weeks | Pluggable MQ | Abstract MqInboundAdapter SPI. RocketMQ / Pulsar adapters. Configuration-driven MQ switching; zero business code changes. |
| **P4: Governance** | Ongoing | Visual management | Admin Dashboard: Pipeline registry viewer, routing topology visualization, dynamic parameter tuning, runtime metrics dashboard. Optional dynamic routing rule hot-reload. |

---

## Appendix: Diagram File Index

All architecture diagrams are in Mermaid format, located in this directory:

| File | Description | Rendering |
|------|-------------|-----------|
| `01-architecture-overview.mmd` | Hexagonal architecture overview | VS Code Mermaid plugin / mermaid.live |
| `02-parallel-consumer-flow.mmd` | Full parallel consumer sequence diagram | Same as above |
| `03-class-design.mmd` | Core class/interface relationship diagram | Same as above |
| `04-module-dependency.mmd` | Maven module dependency graph | Same as above |
| `05-offset-tracker-state.mmd` | OffsetTracker state diagram | Same as above |
| `06-backpressure-lifecycle.mmd` | Backpressure state machine | Same as above |
