# EventFlow Starter — 技术设计文档

> 基于 Spring Boot 的消息/事件处理框架，面向 OceanBase CDC + Kafka 场景，支持 50+ 业务项目复用。

---

## 目录

1. [项目背景与目标](#1-项目背景与目标)
2. [架构设计](#2-架构设计)
3. [并发模型](#3-并发模型)
4. [Maven 工程结构](#4-maven-工程结构)
5. [核心模型与接口](#5-核心模型与接口)
6. [高阶机制详解](#6-高阶机制详解)
7. [Spring Boot Starter 装配](#7-spring-boot-starter-装配)
8. [配置参考](#8-配置参考)
9. [测试脚手架](#9-测试脚手架)
10. [业务接入示例](#10-业务接入示例)
11. [已识别风险与修复方案](#11-已识别风险与修复方案)
12. [演进路线图](#12-演进路线图)

---

## 1. 项目背景与目标

### 1.1 现状痛点

- **数据混流**：OceanBase CDC 将多个物理表的变更数据同步到同一个 Kafka Topic，消费端必须自行做事件识别与路由。
- **强耦合**：业务代码直接依赖 Kafka SDK，后续新增 HTTP 等入口时改造成本高。
- **并发难题**：单 Partition 数据量极大，单线程消费吞吐不足；简单多线程消费会导致乱序和 Offset 丢失。
- **重复造轮子**：防重、背压、DLT、优雅停机等非功能需求在各项目中重复实现，质量参差不齐。

### 1.2 设计目标

| 目标 | 描述 |
|------|------|
| **业务极简** | 业务侧只需一个注解 + 一个接口实现，10 行代码完成 CDC 接入 |
| **架构防腐** | 六边形架构，核心引擎零 Kafka 依赖，未来切换 MQ 或新增入口时业务代码零改动 |
| **并发安全** | Ordered/Concurrent 双模式 + OffsetTracker 滑动窗口，杜绝丢数据和乱序 |
| **背压自适应** | 基于 pause/resume 的精准背压，从根源防 OOM |
| **可观测** | 内置 OpenTelemetry Tracing + Micrometer Metrics，开箱即用 |
| **可测试** | 提供测试脚手架，脱离真实 Kafka 即可验证 Pipeline 逻辑 |
| **MVP 优先** | 第一版能跑通、可复用、可验证、持续演进 |

---

## 2. 架构设计

### 2.1 架构模式

采用 **Ports & Adapters（六边形架构）** 设计思想，分为三层：

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

### 2.2 核心原则

1. **核心层防腐**：核心引擎只定义接口契约（Port），绝不引入 Kafka/HTTP 的特定 SDK。
2. **统一事件模型**：所有入口数据统一转换为 Envelope（参考 CloudEvents 规范）。
3. **SPI 可扩展**：Router、IdempotentFilter、OutboundPort 均为 SPI 接口，可替换实现。

### 2.3 架构图索引

| 图表 | 文件 | 说明 |
|------|------|------|
| 六边形架构全景 | `01-architecture-overview.mmd` | 三层结构 + 组件关系 + 数据流 |
| 并发消费时序 | `02-parallel-consumer-flow.mmd` | 6 个阶段的完整交互序列 |
| 核心类关系 | `03-class-design.mmd` | 接口、模型、适配器的类图 |
| 模块依赖 | `04-module-dependency.mmd` | Maven 模块间依赖关系 |
| Offset 窗口状态 | `05-offset-tracker-state.mmd` | 滑动窗口扫描与水位计算 |
| 背压状态机 | `06-backpressure-lifecycle.mmd` | RUNNING/PAUSED 切换 + 迟滞原理 |

---

## 3. 并发模型

### 3.1 双模式设计

框架提供两种并发模式，由 Pipeline 声明，Dispatcher 统一调度：

| 维度 | Ordered Mode（保序模式） | Concurrent Mode（无序模式） |
|------|--------------------------|----------------------------|
| 适用场景 | 同一主键必须串行处理（如账户余额） | 无顺序依赖，追求最大吞吐（如日志同步） |
| 线程模型 | N 个 SingleThreadExecutor 构成 Worker 矩阵 | 共享 ThreadPoolExecutor |
| 分发策略 | `hash(table + pk) % N` → 固定到某个 Worker | 直接 submit 到线程池 |
| 保序粒度 | 同一 table+pk 严格有序 | 无保序保证 |
| 声明方式 | `@EventPipeline(ordered = true)` | `@EventPipeline(ordered = false)` |

### 3.2 Ordered 模式流转路径

```
poll() → 反序列化 → 防重 → Router → RouteKey
  → PipelineRegistry 匹配（ordered=true）
  → hash(table+pk) % N → Worker[i] 串行执行
  → Pipeline (filter → transform → enrich → handler)
  → OutboundPublisher.publish()
  → OffsetTracker.complete()
```

### 3.3 Concurrent 模式流转路径

```
poll() → 反序列化 → 防重 → Router → RouteKey
  → PipelineRegistry 匹配（ordered=false）
  → SharedThreadPool 并发执行
  → Pipeline (filter → transform → enrich → handler)
  → OutboundPublisher.publish()
  → OffsetTracker.complete()
```

### 3.4 关键约束

- 两种模式共享同一套 OffsetTracker 和背压控制逻辑。
- Offset 安全性和背压能力与模式无关，完全一致。
- 一条消息可以 fan-out 到多个 Pipeline（可能混合 Ordered 和 Concurrent），OffsetTracker 通过引用计数确保所有 Pipeline 完成后才允许 Offset 推进。

---

## 4. Maven 工程结构

```
eventflow/
├── pom.xml                              # Parent BOM
│
├── eventflow-core/                      # 核心引擎（零外部依赖）
│   ├── model/                           #   Envelope, RouteKey, ProcessingResult, Destination
│   ├── port/                            #   InboundPort, OutboundPort (SPI)
│   ├── routing/                         #   Router, PipelineRegistry
│   ├── pipeline/                        #   Pipeline, PipelineStep
│   ├── dispatch/                        #   Dispatcher, OffsetTracker, BackpressureController
│   ├── idempotent/                      #   IdempotentFilter (SPI)
│   ├── observe/                         #   EventFlowTracer (OTel 抽象)
│   └── annotation/                      #   @EventPipeline, @EventHandler
│
├── eventflow-kafka/                     # Kafka 适配器
│   ├── KafkaInboundAdapter.java         #   含 pause/resume 背压
│   ├── KafkaOutboundAdapter.java
│   ├── KafkaEnvelopeConverter.java      #   CDC JSON → Envelope
│   └── KafkaDltPublisher.java           #   毒药消息旁路
│
├── eventflow-spring-boot-starter/       # 自动装配
│   ├── EventFlowAutoConfiguration.java
│   ├── EventFlowProperties.java
│   └── EventFlowRegistrar.java          #   扫描 @EventPipeline
│
├── eventflow-test/                      # 测试脚手架
│   ├── @EventProcessingTest
│   ├── MockInboundPort.java
│   ├── CaptureOutboundPort.java
│   └── EnvelopeFixtures.java
│
└── eventflow-sample/                    # 使用示例
```

### 4.1 依赖关系

| 模块 | 依赖 | 说明 |
|------|------|------|
| `eventflow-core` | slf4j-api, jackson-core; optional: otel-api, micrometer-core | 零 MQ 依赖 |
| `eventflow-kafka` | eventflow-core + kafka-clients | 实现 InboundPort / OutboundPort |
| `eventflow-spring-boot-starter` | eventflow-core + eventflow-kafka + spring-boot-autoconfigure | 自动装配 |
| `eventflow-test` | eventflow-core + spring-boot-test | 不依赖 Kafka |

---

## 5. 核心模型与接口

### 5.1 Envelope（统一事件模型）

参考 CloudEvents 规范，所有入站数据统一转换为此模型：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | String | 唯一 ID，默认 UUID |
| source | String | 来源标识，如 `cdc://oceanbase` |
| type | String | 事件类型，如 `cdc.row_change` |
| time | Instant | 事件产生时间 |
| subject | String | 业务主键，如 `order:12345` |
| data | JsonNode | 业务载荷（CDC row data） |
| headers | Map\<String, String\> | 扩展头：table, op, pk, txId 等 |
| sourceMeta | SourceMeta | 框架内部使用：topic, partition, offset |

**设计约束**：Envelope 必须是不可变对象（Immutable）。所有字段 final，修改通过 `withData()` / `withHeader()` 返回新实例。Fan-out 时对每个 Pipeline 传入 deepCopy 副本，防止并发修改。

### 5.2 RouteKey

```
RouteKey(table="t_order", operation="INSERT")
```

支持通配匹配：`t_order:*` 匹配所有操作类型。

### 5.3 ProcessingResult（密封接口）

| 子类型 | 含义 | 后续动作 |
|--------|------|----------|
| Success(List\<Destination\>) | 处理成功 | 发送到目标 Destination，然后 complete |
| Filtered(String reason) | 被过滤 | 直接 complete |
| Failed(Throwable, boolean retryable) | 处理失败 | 发 DLT；DLT 成功则 complete，DLT 失败则卡窗口告警 |

### 5.4 核心接口清单

| 接口 | 职责 | 默认实现 |
|------|------|----------|
| Router | Envelope → RouteKey | HeaderBasedRouter（从 headers 提取 table/op） |
| PipelineStep | 单步处理（filter/transform/enrich） | 业务自定义 |
| EventHandler | 核心业务逻辑入口 | 业务实现 |
| PipelineRegistry | RouteKey → List\<PipelineDefinition\> | 内置，自动注册 |
| IdempotentFilter | 防重判断 | CaffeineIdempotentFilter（MVP），Redis（未来） |
| OutboundPort | 出站发送 | KafkaOutboundAdapter, HttpOutboundAdapter |
| InboundPort | 入站接入 | KafkaInboundAdapter, MockInboundPort |

---

## 6. 高阶机制详解

### 6.1 OffsetTracker（高水位滑动窗口）

**问题**：并发消费时，高 Offset 的消息可能先于低 Offset 完成。如果直接提交高 Offset，应用崩溃时低 Offset 的消息会丢失。

**方案**：每个 TopicPartition 一个 OffsetTracker 实例，维护滑动窗口：

1. **登记**：消息分发前，`register(offset, pipelineCount)` 在窗口中创建条目，记录引用计数。
2. **完成**：每个 Pipeline 执行完毕后，`complete(offset)` 将引用计数减 1。
3. **提交**：从最小 Offset 开始连续扫描，只提交引用计数归零的连续区间的最大 Offset + 1。

**示例**：

```
窗口状态: [100:done] [101:done] [102:pending(2)] [103:done] [104:pending(1)]

扫描过程:
  100 → done → 移除
  101 → done → 移除
  102 → pending → 停止!

committableOffset = 102 (即 101+1，Kafka 提交语义)
inflight = 3 (102, 103, 104 仍在窗口中)
```

**实现选择**：使用 ConcurrentSkipListMap + AtomicInteger，register/committableOffset 仅由 poll 线程调用（单线程安全），complete 由 Worker 线程调用（CAS 原子减），无锁竞争。

### 6.2 背压控制（Backpressure）

**问题**：如果消费速度远大于处理速度，inflight 消息无限堆积导致 OOM。

**方案**：基于 Kafka 原生 pause/resume API 实现精准背压：

| 状态 | 触发条件 | 动作 |
|------|----------|------|
| RUNNING → PAUSED | inflight >= highWaterMark (默认 1000) | `consumer.pause(partition)` |
| PAUSED → RUNNING | inflight <= lowWaterMark (默认 500) | `consumer.resume(partition)` |

**迟滞区间**（high=1000, low=500）的意义：避免在阈值附近频繁 pause/resume 振荡。如果 pause 和 resume 阈值相同（如都是 1000），则 inflight 在 999 和 1000 之间反复切换时会产生大量无意义的 pause/resume 调用。

**关键设计**：pause/resume 是唯一的流控闸门。Dispatcher 线程池不设置拒绝策略（队列 unbounded），防止两套背压互相冲突。

### 6.3 动态并发分发（Dispatcher）

- **Ordered 模式**：维护 N 个 SingleThreadExecutor（默认 N=16）。通过 `hash(table + pk) % N` 将同主键消息固定到同一个 Worker，保证局部串行。
- **Concurrent 模式**：共享 ThreadPoolExecutor（core=8, max=32），消息直接 submit，无序最大吞吐。
- **策略声明**：通过 `@EventPipeline(ordered=true/false)` 注解，或配置文件声明。

### 6.4 防重（Idempotent Filter）

- 默认策略：基于 `table + pk + timestamp` 或 `txId` 生成防重 Key。
- MVP 实现：Caffeine 本地缓存（10 万条，TTL 10 分钟）。
- SPI 扩展：后续可替换为 Redis 实现，支持多实例共享。
- **已知局限**：本地缓存在重启后丢失、多实例间不共享。文档必须明确告知使用方。

### 6.5 毒药消息处理

JSON 反序列化开启宽容模式。彻底无法解析的消息：
1. 在 Inbound 层面直接旁路到 DLT（Dead Letter Topic）。
2. 在 OffsetTracker 中立即 register + complete，不卡窗口。
3. 严禁无限重试。

### 6.6 优雅停机

执行顺序：
1. `consumer.pause(ALL)` — 切断数据源
2. `running = false` — 退出 poll 循环
3. `dispatcher.shutdown(timeout)` — 等待 Worker 队列排空
4. `commitCompletedOffsets()` — 最终提交
5. `consumer.close()` — 释放连接
6. Spring 容器继续销毁其他 Bean

### 6.7 Rebalance 处理

`onPartitionsRevoked` 时：
1. 阻塞等待被 revoke 的 partition 的 inflight 消息全部完成（awaitDrain，超时 10 秒）。
2. 提交该 partition 的最终 Offset。
3. 清理 Tracker 和 pause 状态。

---

## 7. Spring Boot Starter 装配

### 7.1 自动配置

`EventFlowAutoConfiguration` 通过 `@AutoConfiguration` + `@ConditionalOnClass` 实现：

- 默认装配 HeaderBasedRouter、CaffeineIdempotentFilter、PipelineRegistry、Dispatcher、CompositeOutboundPublisher
- classpath 存在 kafka-clients 时自动装配 KafkaInboundAdapter、KafkaOutboundAdapter
- 所有核心 Bean 均标注 `@ConditionalOnMissingBean`，业务可覆盖替换

### 7.2 Pipeline 自动注册

`EventFlowRegistrar` 实现 `SmartInitializingSingleton`，启动时扫描所有标注 `@EventPipeline` 的 Bean，自动注册到 PipelineRegistry。

---

## 8. 配置参考

```yaml
eventflow:
  enabled: true

  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP:localhost:9092}
    consumer:
      group-id: ${spring.application.name}-eventflow
      auto-offset-reset: earliest
      enable-auto-commit: false           # 框架管理 Offset 提交
      max-poll-records: 500
    topics:
      - cdc_oceanbase_main
    dlt:
      topic-suffix: .DLT
      enabled: true

  dispatch:
    ordered-worker-count: 16              # Ordered 模式 Worker 数量
    concurrent-core-size: 8               # Concurrent 核心线程
    concurrent-max-size: 32               # Concurrent 最大线程

  backpressure:
    high-water-mark: 1000                 # inflight >= 此值 → pause
    low-water-mark: 500                   # inflight <= 此值 → resume

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

## 9. 测试脚手架

### 9.1 设计理念

业务团队必须能在不启动 Kafka 的情况下验证 Pipeline 逻辑。框架提供：

| 组件 | 职责 |
|------|------|
| `@EventProcessingTest` | 组合注解：加载 Spring 上下文，禁用 Kafka，注入 Mock 组件 |
| `MockInboundPort` | 直接注入 Envelope，同步执行 Pipeline，返回 ProcessingResult |
| `CaptureOutboundPort` | 捕获所有输出 Destination，供断言使用 |
| `EnvelopeFixtures` | 快速构造 CDC INSERT/UPDATE/DELETE 测试事件 |

### 9.2 使用方式

```
@EventProcessingTest 标注测试类
→ 自动注入 MockInboundPort + CaptureOutboundPort
→ 用 EnvelopeFixtures 构造事件
→ inbound.send(event) 触发 Pipeline
→ 断言 result 和 outbound.getCaptured()
```

无需 Testcontainers，无需 Embedded Kafka，秒级执行。

---

## 10. 业务接入示例

业务侧完整接入步骤：

**步骤 1**：引入依赖 `eventflow-spring-boot-starter`

**步骤 2**：编写 Handler（全部业务代码）

```java
@EventPipeline(routeKey = "t_order:*", ordered = true)
public class OrderCdcHandler implements EventHandler {

    @Override
    public ProcessingResult handle(Envelope envelope) {
        String op = envelope.getHeaders().get("op");
        if ("DELETE".equals(op)) {
            return new ProcessingResult.Filtered("skip delete");
        }
        // 业务处理...
        return new ProcessingResult.Success(List.of(
            new Destination("kafka", "order-sync-out", payload)
        ));
    }
}
```

**步骤 3**：配置 application.yml（Kafka 连接 + 按需调参）

**完毕。** 无需编写 Kafka Consumer、无需管理 Offset、无需实现线程池、无需处理背压。

---

## 11. 已识别风险与修复方案

### 11.1 P0 级别（必须在 MVP 中修复）

| # | 风险 | 影响 | 修复方案 |
|---|------|------|----------|
| 1 | Fan-out 下 Offset 被提前提交 | 数据丢失 | OffsetTracker 引入引用计数，register 时声明 Pipeline 数量，所有 Pipeline 完成才允许 Offset 推进 |
| 2 | CallerRunsPolicy 阻塞 Poll 线程 | 全 Partition 停滞，触发 Rebalance | 去掉线程池拒绝策略，pause/resume 作为唯一背压闸门 |
| 3 | Rebalance 期间 In-flight 无处理 | 数据丢失 + 重复 | 实现 ConsumerRebalanceListener，revoke 时 awaitDrain + 提交最终 Offset |
| 4 | 失败消息无条件标记 Complete | 数据丢失 | DLT 投递成功才 complete；DLT 也失败则卡窗口 + 告警 |

### 11.2 P1 级别（建议 MVP 一并解决）

| # | 风险 | 影响 | 修复方案 |
|---|------|------|----------|
| 5 | OffsetTracker 锁竞争 | 高吞吐下性能瓶颈 | ConcurrentSkipListMap + AtomicInteger 无锁设计 |
| 6 | Offset Commit 过于频繁 | Broker 压力 | 按时间间隔提交（如每 5 秒） |
| 7 | Ordered Hash 倾斜不可见 | 热点 Worker 过载 | 暴露每个 Worker 队列深度 Metric |
| 8 | Envelope 可变性并发风险 | 脏数据 | 不可变设计 + Fan-out 时 deepCopy |
| 9 | Outbound 失败但 Offset 已提交 | 数据被吞 | 先 publish 后 complete |
| 10 | Metrics 规划缺失 | 无法运维 | 内置 Micrometer 埋点（见下表） |

### 11.3 MVP 必须内置的 Metrics

| Metric 名称 | 类型 | 标签 |
|-------------|------|------|
| eventflow.inflight | Gauge | partition |
| eventflow.offset.committed | Counter | partition |
| eventflow.pipeline.duration | Timer | routeKey, status |
| eventflow.pipeline.error | Counter | routeKey, errorType |
| eventflow.dispatch.queue.size | Gauge | mode, slot |
| eventflow.backpressure.pause | Counter | partition |
| eventflow.dlt.sent | Counter | topic, reason |
| eventflow.idempotent.hit | Counter | — |

### 11.4 架构层面补充

- **单 Poll 线程瓶颈**：预留"每个 Topic 一个 KafkaInboundAdapter 实例"的拆分能力，MVP 可先单线程但架构上不写死。
- **IdempotentFilter 局限**：Caffeine 本地缓存重启后丢失、多实例不共享，文档必须明确说明，预留 Redis SPI。

---

## 12. 演进路线图

| 阶段 | 周期 | 目标 | 交付物 |
|------|------|------|--------|
| **P0: MVP** | 4 周 | 跑通核心链路 | core + kafka + starter + test 四模块。双模式 Dispatcher、引用计数 OffsetTracker、pause/resume 背压、DLT、Caffeine 防重、@EventPipeline 注解驱动、@EventProcessingTest 测试脚手架。内部 2-3 个项目试点。 |
| **P1: 加固** | 3 周 | 生产可靠性 | OpenTelemetry 全链路（Span + Metrics + MDC traceId）。Grafana 仪表盘模板。优雅停机全链路验证。防重可选 Redis 后端。Pipeline Step 链式编排 DSL。 |
| **P2: 扩展入口** | 3 周 | 多入口支持 | eventflow-http 模块：HTTP Controller → Envelope → 相同 Pipeline。统一 API 鉴权。 |
| **P3: 多 MQ** | 3 周 | MQ 可插拔 | 抽象 MqInboundAdapter SPI，实现 RocketMQ / Pulsar 适配器。配置化切换，业务零改动。 |
| **P4: 治理平台** | 持续 | 可视化管理 | Admin Dashboard：Pipeline 注册表、路由拓扑可视化、动态调参、运行时 Metrics 大盘。可选动态路由规则热更新。 |

---

## 附录：图表文件索引

所有架构图均使用 Mermaid 格式，位于本目录下：

| 文件 | 说明 | 渲染方式 |
|------|------|----------|
| `01-architecture-overview.mmd` | 六边形架构全景图 | VS Code Mermaid 插件 / mermaid.live |
| `02-parallel-consumer-flow.mmd` | 并发消费完整时序图 | 同上 |
| `03-class-design.mmd` | 核心类/接口关系图 | 同上 |
| `04-module-dependency.mmd` | Maven 模块依赖图 | 同上 |
| `05-offset-tracker-state.mmd` | OffsetTracker 状态图 | 同上 |
| `06-backpressure-lifecycle.mmd` | 背压状态机 | 同上 |
