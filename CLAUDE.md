# CLAUDE.md — EventFlow Project Context

## Project Overview

EventFlow 是一个基于 Spring Boot 的 CDC 事件处理框架（Starter），面向 OceanBase CDC + Kafka 场景，目标支撑 50+ 内部业务项目复用。

## Architecture

六边形架构（Ports & Adapters），核心引擎零 Kafka 依赖，业务代码零 OTel 依赖。

```
Inbound (Kafka/REST/Test) → Envelope → IdempotentFilter → Router
  → PipelineRegistry → Dispatcher → PreSteps → EventHandler → OutboundPublisher
```

## Module Structure

```
eventflow/
├── eventflow-core                  # 核心引擎：Dispatcher, Pipeline, OffsetTracker, BackpressureController, EventFlowTracer
├── eventflow-kafka                 # Kafka 适配器：KafkaInboundAdapter, KafkaOutboundAdapter, DLT
├── eventflow-spring-boot-starter   # 自动装配：EventFlowAutoConfiguration, @EventPipeline, EventFlowProperties
├── eventflow-test                  # 测试脚手架：MockInboundPort, CaptureOutboundPort, EnvelopeFixtures
├── eventflow-sample                # 示例应用：REST API, LoadTest, Grafana/Tempo/Prometheus 集成
└── docs/                           # 设计文档（中英文）+ Mermaid 架构图
```

## Tech Stack

- Java 17, Spring Boot 3.5.13
- OpenTelemetry SDK 1.49.0 (tracing), Micrometer (metrics)
- Caffeine (idempotent cache)
- Apache Kafka (inbound/outbound)
- Grafana + Tempo + Prometheus (observability, no Jaeger)

## Package Name

`com.hsbc.pluse` — all modules use this base package.

## Key Design Principles

1. **Business code = pure POJO**: EventHandler and PipelineStep implementations must have ZERO dependency on EventFlowTracer, OpenTelemetry, or any framework class. All tracing is automatic at the Dispatcher level.
2. **Framework-level auto-tracing**: Every pipeline execution automatically creates spans for idempotent check, pre-steps, handler, outbound publish, and offset tracking.
3. **Dual-mode dispatch**: Ordered (per-key serial via worker slot array) and Concurrent (shared ThreadPoolExecutor). Configured per-pipeline via `PipelineDefinition.ordered()`.
4. **Cross-thread context propagation**: Dispatcher captures `Context.current()` in the caller thread and passes it to `tracedChild()` in the worker thread.

## Build & Run

```bash
# Build (uses custom Maven settings for Aliyun mirror)
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/zulu-17.jdk/Contents/Home
mvn clean install -s mvn-settings.xml -DskipTests

# Run sample app (without auto-demo events)
cd eventflow-sample
mvn spring-boot:run -s ../mvn-settings.xml -Dspring-boot.run.arguments="--demo.auto-run=false"
```

## Observability Stack (local, no Docker)

Docker Desktop is incompatible with the dev machine (macOS 13.5). All observability tools run as local binaries:

- **Tempo** (tracing backend): `/tmp/tempo` with config `eventflow-sample/tempo.yml`, port 4317 (OTLP gRPC) / 3200 (HTTP)
- **Prometheus**: `/tmp/prometheus-2.50.1.darwin-amd64/prometheus` with config `eventflow-sample/prometheus.yml`, port 9090
- **Grafana**: `/tmp/grafana-v10.3.3/bin/grafana`, port 3000 (admin/admin)

## REST API Endpoints (sample module)

- `POST /api/order` — submit order CDC event
- `POST /api/payment` — submit payment CDC event (goes through 3 pre-steps)
- `POST /api/load-test` — batch load test `{"eventCount":500,"table":"t_order","concurrency":50}`
- `POST /api/load-test/mixed` — mixed order+payment load test
- `GET /api/metrics` — real-time dispatcher performance stats

All responses include `traceId` and `grafanaUrl` for trace lookup.

## Important Files

- `eventflow-core/.../dispatch/Dispatcher.java` — core dispatcher with all framework-level tracing
- `eventflow-core/.../dispatch/DispatcherStats.java` — thread-safe performance statistics (reservoir sampling)
- `eventflow-core/.../observe/EventFlowTracer.java` — OTel wrapper (traced, tracedChild, tracedInternal)
- `eventflow-spring-boot-starter/.../boot/EventFlowAutoConfiguration.java` — all bean wiring
- `eventflow-sample/.../sample/OtelConfig.java` — OTel SDK setup (BatchSpanProcessor, 1s flush)

## Code Conventions

- Sealed interfaces for results: `ProcessingResult.Success | Filtered | Failed`
- Records for immutable data: `Envelope`, `Destination`, `SourceMeta`, `RouteKey`
- `@EventPipeline` annotation for auto-registration via `EventFlowRegistrar`
- Pre-steps registered manually via `DemoPipelineConfig` for pipelines needing enrichment chains
