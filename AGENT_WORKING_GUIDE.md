# AGENT_WORKING_GUIDE.md

> 由 `eventflow/CLAUDE.md`（主）+ `eventflow/eventflow-sample/CLAUDE.md`（补充）提炼，用于我在此仓库中的稳定执行。

## 1. 我对项目的工作理解

- 项目是一个 Spring Boot CDC 事件处理框架（Starter），核心场景是 OceanBase CDC + Kafka。
- 架构采用六边形（Ports & Adapters），核心引擎不依赖 Kafka，业务处理代码不依赖 OTel。
- 核心流水线：
  - `Inbound -> Envelope -> IdempotentFilter -> Router -> PipelineRegistry -> Dispatcher -> PreSteps -> EventHandler -> OutboundPublisher`

## 2. 必须遵守的代码约束

- 业务处理层（`EventHandler` / `PipelineStep`）必须保持 POJO，不引入 tracing/framework 细节。
- tracing 由框架统一负责（Dispatcher/EventFlowTracer），业务代码不手动埋 OTel。
- 按 pipeline 配置支持两种调度模式：
  - Ordered：按 key 串行
  - Concurrent：线程池并发
- 跨线程必须保持上下文传播（caller `Context` -> worker child span）。
- 基础包名固定：`com.hsbc.pluse`。
- 倾向使用：
  - `record` 表达不可变数据（如 Envelope/RouteKey）
  - sealed 结果类型（如 `ProcessingResult.Success | Filtered | Failed`）

## 3. 模块与重点文件

- `eventflow-core`：调度、流水线、背压、追踪核心
  - 重点：`dispatch/Dispatcher.java`, `dispatch/DispatcherStats.java`, `observe/EventFlowTracer.java`
- `eventflow-kafka`：Kafka 入/出站与 DLT 适配
- `eventflow-spring-boot-starter`：自动装配与注解注册
  - 重点：`boot/EventFlowAutoConfiguration.java`
- `eventflow-test`：测试脚手架
- `eventflow-sample`：可运行样例 + 观测 + 压测
  - 重点：`sample/OtelConfig.java`

## 4. 标准工作流程（我执行任务默认按此顺序）

1. 先定位任务模块（core/starter/sample），确认是否影响 pipeline 行为或 tracing 边界。
2. 若涉及业务处理逻辑，先检查是否意外引入了框架/OTel耦合。
3. 若涉及并发或顺序语义，确认 `ordered()` 配置与线程模型一致。
4. 若涉及观测问题，先看 dispatcher span 与指标是否完整，再看业务侧数据。
5. 修改后至少做一次本地构建（必要时跳测），并说明未执行项与风险。

## 5. 常用命令基线

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/zulu-17.jdk/Contents/Home

# 项目根目录构建
cd /Users/dailu/aidemo/eventflow
mvn clean install -s mvn-settings.xml -DskipTests

# 运行 sample（建议关闭 auto demo）
cd /Users/dailu/aidemo/eventflow/eventflow-sample
mvn spring-boot:run -s ../mvn-settings.xml -Dspring-boot.run.arguments="--demo.auto-run=false"
```

## 6. POC（eventflow-sample）专项执行要点

- 比较策略：
  - A：Kafka -> 直接处理（低时延）
  - B：Kafka -> DB 落库 -> Scheduler 二阶段处理（有调度延迟）
- E2E 对比指标：
  - `poc_e2e_duration_seconds{strategy=A|B}`
  - `poc_events_processed_total{strategy=A|B}`
- 关键配置提醒：
  - fan-out 场景下 `idempotent.enabled` 必须是 `false`，否则第二条 pipeline 会被去重。
  - 若 B 策略积压，优先检查 `@EnableScheduling` 与 `eventflow.poc.scheduler-delay-ms`。
- 压测入口优先用 REST：`POST /api/poc/load-test`（无需重启应用）。

## 7. 故障排查速记

- 端口冲突：`lsof -i :<port>`
- Kafka 消费位点异常：重置 consumer group offsets
- Grafana 面板无 TraceQL 数据：在 Grafana Explore 页面查
- 旧 schema 升级：确保 `event_store.send_timestamp` 已存在
- 指标为 0：确认事件包含 `sendTimestamp`（REST 手工事件通常不含）

## 8. 我在该仓库的默认协作承诺

- 先保证正确性，再做性能与可观测性收敛。
- 任何改动都优先保持：业务无 OTel 侵入、核心无 Kafka 侵入、边界清晰。
- 输出时明确说明：改了什么、为什么、验证了什么、还剩什么风险。

